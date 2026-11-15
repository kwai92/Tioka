package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.ontology.api.schema.ReasonCode;
import com.tioka.ontology.api.schema.SchemaError;
import com.tioka.ontology.domain.cel.PlatformCelFunctions;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code x-tioka-rules} 求值器（SCH-08 / ADR-066 §5.1）。
 *
 * <p>与 ONT-04 的 {@link com.tioka.ontology.application.rule.RuleEvaluator} 共用同一套
 * CEL 运行时与函数库，但变量口径不同：规则引擎面向 {@code model_json}（变量 {@code m}，
 * 带 status/value 包装），本求值器面向**已解析的 payload**（变量 {@code self} = 当前对象、
 * {@code root} = 根对象）。
 *
 * <p>求值超时 50 ms → {@link ReasonCode#RULE_TIMEOUT}；求值/结果非布尔 → {@link ReasonCode#RULE}。
 * 规则只读 payload 本身，不读外部数据（需要外部数据的检查写成领域钩子）。
 */
public class SchemaRuleEvaluator {

    static final long EVAL_TIMEOUT_MS = 50L;

    private static final Logger log = LoggerFactory.getLogger(SchemaRuleEvaluator.class);

    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final ConcurrentMap<String, CelRuntime.Program> cache = new ConcurrentHashMap<>();
    private final ExecutorService evalPool;
    private final long evalTimeoutMs;

    public SchemaRuleEvaluator() {
        this(EVAL_TIMEOUT_MS);
    }

    public SchemaRuleEvaluator(long evalTimeoutMs) {
        this.compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addVar("root", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("self", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addLibraries(CelOptionalLibrary.INSTANCE)
                .addFunctionDeclarations(PlatformCelFunctions.declarations())
                .build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder()
                .addFunctionBindings(PlatformCelFunctions.bindings())
                .addLibraries(CelOptionalLibrary.INSTANCE)
                .build();
        this.evalPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sch-rule-eval");
            t.setDaemon(true);
            return t;
        });
        this.evalTimeoutMs = evalTimeoutMs;
        warmUp();
    }

    /**
     * 冷启动预热：JVM 首次求值要加载 CEL 运行时类并创建求值线程，这些一次性开销若计入
     * {@link #EVAL_TIMEOUT_MS} 预算，会让启动后最初几条规则的合法产出被误判为
     * {@link ReasonCode#RULE_TIMEOUT}。故在构造线程上先私有求值一次并预建求值线程，
     * 把类加载/JIT 移出 50 ms 计时窗口。任何预热异常都忽略（不影响正确性）。
     */
    private void warmUp() {
        try {
            dev.cel.common.CelValidationResult r = compiler.compile("1 + 1");
            if (!r.hasError()) {
                runtime.createProgram(r.getAst()).eval(Map.of());
            }
        } catch (Throwable ignore) {
            // 预热失败不影响正确性
        }
        try {
            // 规则最常见的形态是 dyn 变量的比较（如 self.endDate >= self.startDate）；
            // 该路径的 dyn 派发类只在首次比较时加载，若计入 50 ms 窗口会把首条合法规则
            // 误判为 RULE_TIMEOUT。此处按同类表达式预热一次，把类加载移出计时窗口。
            dev.cel.common.CelValidationResult r = compiler.compile("self.a >= self.b");
            if (!r.hasError()) {
                runtime.createProgram(r.getAst())
                        .eval(Map.of("self", Map.of("a", "b", "b", "a")));
            }
        } catch (Throwable ignore) {
            // 预热失败不影响正确性
        }
        try {
            evalPool.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Throwable ignore) {
            // 预建求值线程失败不影响正确性
        }
    }

    // ==================== 发布期：提取 + 编译 ====================

    /** 从权威 Schema 中收集全部 {@code x-tioka-rules}。 */
    public static List<SchemaRule> collect(JsonNode schema) {
        List<SchemaRule> out = new ArrayList<>();
        collectInto(schema, "", out);
        return out;
    }

    private static void collectInto(JsonNode node, String pointer, List<SchemaRule> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectInto(node.get(i), pointer + "/" + i, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode rules = node.get("x-tioka-rules");
        if (rules != null && rules.isArray()) {
            for (int i = 0; i < rules.size(); i++) {
                JsonNode r = rules.get(i);
                if (r == null || !r.isObject()) {
                    continue;
                }
                String id = r.path("id").asText(null);
                String expr = r.path("expr").asText(null);
                String message = r.path("message").asText("");
                SchemaRule.Severity severity = "WARN".equalsIgnoreCase(r.path("severity").asText("ERROR"))
                        ? SchemaRule.Severity.WARN
                        : SchemaRule.Severity.ERROR;
                boolean hint = r.path("hint").asBoolean(false);
                out.add(new SchemaRule(id, expr, message, severity, hint,
                        pointer + "/x-tioka-rules/" + i));
            }
        }
        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                collectInto(e.getValue(), pointer + "/properties/" + e.getKey(), out);
            }
        }
        for (String key : List.of("items", "additionalProperties")) {
            JsonNode child = node.get(key);
            if (child != null && child.isObject()) {
                collectInto(child, pointer + "/" + key, out);
            }
        }
        JsonNode oneOf = node.get("oneOf");
        if (oneOf != null && oneOf.isArray()) {
            for (int i = 0; i < oneOf.size(); i++) {
                collectInto(oneOf.get(i), pointer + "/oneOf/" + i, out);
            }
        }
    }

    /**
     * 发布期编译：全部规则编译通过返回空清单；否则返回编译失败问题（Schema 发布被拒）。
     */
    public List<SchemaError> compileAll(JsonNode schema) {
        List<SchemaError> issues = new ArrayList<>();
        for (SchemaRule rule : collect(schema)) {
            if (rule.id() == null || rule.id().isBlank()) {
                issues.add(new SchemaError(rule.pointer(), "RULE_COMPILE", "规则缺少 id"));
                continue;
            }
            if (rule.expr() == null || rule.expr().isBlank()) {
                issues.add(new SchemaError(rule.pointer(), "RULE_COMPILE", "规则缺少 expr：" + rule.id()));
                continue;
            }
            try {
                programFor(rule.id(), rule.expr());
            } catch (Exception e) {
                issues.add(new SchemaError(rule.pointer(), "RULE_COMPILE",
                        "规则编译失败 " + rule.id() + "：" + rootMessage(e)));
            }
        }
        return issues;
    }

    // ==================== 运行期：求值 ====================

    /**
     * 按 Schema 标注逐处执行规则。
     *
     * @return 失败清单：{@code ERROR} 与求值错误（{@link ReasonCode#RULE}/{@link ReasonCode#RULE_TIMEOUT}）。
     *         {@code WARN} 违规不在此列，改由 {@link #warnedRules} 取（结局 {@code VALID_WITH_UNKNOWN}）。
     */
    public List<SchemaError> evaluateErrors(JsonNode schema, JsonNode payload) {
        List<SchemaError> errors = new ArrayList<>();
        evalWalk(schema, payload, payload, "", errors, null);
        return errors;
    }

    /** {@code WARN} 级违规的路径（结局 {@code VALID_WITH_UNKNOWN} 的 unknownPaths 之一）。 */
    public List<String> warnedRules(JsonNode schema, JsonNode payload) {
        List<String> warns = new ArrayList<>();
        evalWalk(schema, payload, payload, "", null, warns);
        return warns;
    }

    private void evalWalk(JsonNode schemaNode, JsonNode payloadNode, JsonNode root, String pointer,
                          List<SchemaError> errors, List<String> warns) {
        if (schemaNode == null || payloadNode == null
                || payloadNode.isMissingNode() || payloadNode.isNull()) {
            return;
        }
        if (schemaNode.isObject() && payloadNode.isObject()) {
            JsonNode rules = schemaNode.get("x-tioka-rules");
            if (rules != null && rules.isArray()) {
                evaluateRulesAt(rules, payloadNode, root, pointer, errors, warns);
            }
            JsonNode props = schemaNode.get("properties");
            if (props != null && props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    evalWalk(e.getValue(), payloadNode.get(e.getKey()), root,
                            pointer + "/" + e.getKey(), errors, warns);
                }
            }
            return;
        }
        JsonNode items = schemaNode.isObject() ? schemaNode.get("items") : null;
        if (items != null && payloadNode.isArray()) {
            for (int i = 0; i < payloadNode.size(); i++) {
                evalWalk(items, payloadNode.get(i), root, pointer + "/" + i, errors, warns);
            }
        }
    }

    private void evaluateRulesAt(JsonNode rules, JsonNode self, JsonNode root, String pointer,
                                 List<SchemaError> errors, List<String> warns) {
        for (int i = 0; i < rules.size(); i++) {
            JsonNode r = rules.get(i);
            if (r == null || !r.isObject()) {
                continue;
            }
            String id = r.path("id").asText("rule#" + i);
            String expr = r.path("expr").asText(null);
            String message = r.path("message").asText("");
            boolean warn = "WARN".equalsIgnoreCase(r.path("severity").asText("ERROR"));
            if (expr == null || expr.isBlank()) {
                continue;
            }
            Object raw = evalExpr(id, expr, self, root);
            if (raw == EvalFailure.TIMEOUT) {
                if (errors != null) {
                    errors.add(new SchemaError(pointer, ReasonCode.RULE_TIMEOUT.name(),
                            "规则求值超时 " + id));
                }
                continue;
            }
            if (raw instanceof EvalFailure failure) {
                if (errors != null) {
                    errors.add(new SchemaError(pointer, ReasonCode.RULE.name(),
                            "规则求值失败 " + id + "：" + failure.message()));
                }
                continue;
            }
            if (!(raw instanceof Boolean b)) {
                if (errors != null) {
                    errors.add(new SchemaError(pointer, ReasonCode.RULE.name(),
                            "规则结果非布尔 " + id + "：" + raw));
                }
                continue;
            }
            if (!b) {
                if (warn) {
                    if (warns != null) {
                        warns.add(pointer + "#" + id);
                    }
                } else if (errors != null) {
                    errors.add(new SchemaError(pointer, ReasonCode.RULE.name(),
                            message.isBlank() ? ("规则不满足：" + id) : message));
                }
            }
        }
    }

    Object evalExpr(String ruleId, String expression, JsonNode self, JsonNode root) {
        try {
            CelRuntime.Program program = programFor(ruleId, expression);
            Map<String, Object> activation = new HashMap<>();
            activation.put("root", toCel(root));
            activation.put("self", toCel(self));
            Future<Object> future = evalPool.submit(() -> {
                try {
                    return program.eval(activation);
                } catch (CelEvaluationException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            });
            try {
                return future.get(evalTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return EvalFailure.TIMEOUT;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return new EvalFailure(cause.getMessage() == null ? cause.toString() : cause.getMessage());
            }
        } catch (Exception e) {
            return new EvalFailure(rootMessage(e));
        }
    }

    private CelRuntime.Program programFor(String ruleId, String expression) throws Exception {
        String cacheKey = ruleId + ":" + sha256(expression);
        CelRuntime.Program cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        dev.cel.common.CelValidationResult result = compiler.compile(expression);
        if (result.hasError()) {
            throw new IllegalStateException("CEL 编译失败: " + result.getErrorString());
        }
        CelAbstractSyntaxTree ast = result.getAst();
        CelRuntime.Program program = runtime.createProgram(ast);
        cache.putIfAbsent(cacheKey, program);
        return program;
    }

    /** 普通 JSON → CEL 激活结构（数组保持 List，整数归一为 Long）。 */
    static Object toCel(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode e : node) {
                list.add(toCel(e));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                map.put(e.getKey(), toCel(e.getValue()));
            }
            return map;
        }
        return scalarOf(node);
    }

    /** 标量转换：整数 → Long（cel-java 0.9.1 运行期比较分派要求），其余原样。 */
    private static Object scalarOf(JsonNode node) {
        if (node.isInt() || node.isLong() || node.isShort() || node.isBigInteger()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootMessage(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** 求值失败（超时 / 编译 / 运行错误）。 */
    record EvalFailure(String message) {
        static final EvalFailure TIMEOUT = new EvalFailure("cel_timeout");
    }
}
