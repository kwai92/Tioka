package com.tioka.ontology.application.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.framework.json.CanonicalJson;
import com.tioka.ontology.api.rule.RuleResult;
import com.tioka.ontology.api.rule.RuleSpec;
import com.tioka.ontology.application.pack.CelFunction;
import com.tioka.ontology.domain.ModelPathResolver;
import com.tioka.ontology.domain.cel.ModelValueProvider;
import com.tioka.ontology.domain.cel.PlatformCelFunctions;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.ast.CelExpr;
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
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code rule.evaluate}（ONT-04，M3-03 §9 / ADR-029 第 53 条）。
 *
 * <p>三值语义：applies 为假 → 不适用；requires 任一路径未知 → 缺失、空白 → onBlank、
 * 不适用 → 不适用；check 访问未知值（CEL 运行期分派失败）/ 结果为 none / 求值错误 /
 * 超时（50 ms）→ 缺失并记 error，不让审查整体失败。「待复核」取值按已知值参与计算，
 * 结论附 needsReview = true。
 *
 * <p>CEL 与 ADR-040 行谓词同一运行时（cel-java 0.9.1）；编译缓存按 {@code ruleId + 表达式哈希}。
 * 求值在专用线程池执行（{@link com.tioka.ontology.application.pack.PureFunctionExecutor} 同口径隔离）。
 */
public class RuleEvaluator {

    static final long EVAL_TIMEOUT_MS = 50L;

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final ConcurrentMap<String, CelRuntime.Program> cache = new ConcurrentHashMap<>();
    private final ExecutorService evalPool;
    private final long evalTimeoutMs;

    public RuleEvaluator() {
        this(List.of());
    }

    /** 领域包注册的 CEL 函数（DomainPack.celFunctions）叠加在平台内置函数之上。 */
    public RuleEvaluator(List<CelFunction> packFunctions) {
        this(packFunctions, EVAL_TIMEOUT_MS);
    }

    public RuleEvaluator(List<CelFunction> packFunctions, long evalTimeoutMs) {
        var compilerBuilder = CelCompilerFactory.standardCelCompilerBuilder()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addVar("m", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("cfg", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addLibraries(CelOptionalLibrary.INSTANCE)
                .addFunctionDeclarations(PlatformCelFunctions.declarations());
        List<CelFunctionDecl> packDecls = packFunctionDeclarations(packFunctions);
        if (!packDecls.isEmpty()) {
            compilerBuilder = compilerBuilder.addFunctionDeclarations(packDecls);
        }
        this.compiler = compilerBuilder.build();

        var runtimeBuilder = CelRuntimeFactory.standardCelRuntimeBuilder()
                .addFunctionBindings(PlatformCelFunctions.bindings())
                .addLibraries(CelOptionalLibrary.INSTANCE);
        List<CelRuntime.CelFunctionBinding> packBindings = packFunctionBindings(packFunctions);
        if (!packBindings.isEmpty()) {
            runtimeBuilder = runtimeBuilder.addFunctionBindings(packBindings);
        }
        this.runtime = runtimeBuilder.build();

        this.evalPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ont-cel-eval");
            t.setDaemon(true);
            return t;
        });
        this.evalTimeoutMs = evalTimeoutMs;
    }

    /**
     * 求值一条规则。
     *
     * @param model         实例 model_json（当前确认版本或草稿）
     * @param cfg           阈值 {参数名: 值}
     * @param configVersion 阈值配置版本（记入 RuleResult.params）
     */
    public RuleResult evaluate(RuleSpec rule, JsonNode model, Map<String, Object> cfg, String configVersion) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(model, "model");
        List<RuleResult.Touched> touched = new ArrayList<>();
        List<RuleResult.Param> params = toParams(rule, cfg, configVersion);

        // 1. applies：false → 不适用；求值失败 → error + 缺失
        if (rule.applies() != null && !rule.applies().isBlank()) {
            Object raw = evalExpr(rule.ruleId(), rule.applies(), model, cfg);
            collectTouched(touched, model, touchedPaths(rule.applies()));
            if (raw instanceof EvalFailure failure) {
                return RuleResult.error("applies 求值失败: " + failure.message(), touched, params);
            }
            if (!Boolean.TRUE.equals(raw)) {
                return RuleResult.of(RuleResult.Conclusion.NOT_APPLICABLE, touched, params);
            }
        }

        // 2. requires：未知 → 缺失；不适用 → 不适用；空白 → onBlank；待复核按已知值 + needsReview
        boolean needsReview = false;
        for (String path : rule.requires()) {
            ModelPathResolver.ValueStatus status = statusOfPath(model, path);
            collectTouched(touched, model, List.of(path));
            switch (status) {
                case UNKNOWN -> {
                    return RuleResult.of(RuleResult.Conclusion.MISSING, touched, params);
                }
                case NOT_APPLICABLE -> {
                    return RuleResult.of(RuleResult.Conclusion.NOT_APPLICABLE, touched, params);
                }
                case BLANK -> {
                    return RuleResult.of(onBlankOf(rule), touched, params);
                }
                case REVIEW -> needsReview = true;
                case KNOWN -> {
                    // continue
                }
            }
        }

        // 3. check：optional<bool>；none → 缺失；布尔 → 满足 / 不满足
        Object raw = evalExpr(rule.ruleId(), rule.check(), model, cfg);
        collectTouched(touched, model, touchedPaths(rule.check()));
        if (raw instanceof EvalFailure failure) {
            return RuleResult.error("check 求值失败: " + failure.message(), touched, params);
        }
        RuleResult.Conclusion conclusion;
        if (raw instanceof Optional<?> opt) {
            if (opt.isEmpty()) {
                conclusion = RuleResult.Conclusion.MISSING;
            } else if (opt.get() instanceof Boolean b) {
                conclusion = b ? RuleResult.Conclusion.SATISFIED : RuleResult.Conclusion.NOT_SATISFIED;
            } else {
                return RuleResult.error("check 结果非布尔: " + opt.get(), touched, params);
            }
        } else if (raw instanceof Boolean b) {
            conclusion = b ? RuleResult.Conclusion.SATISFIED : RuleResult.Conclusion.NOT_SATISFIED;
        } else {
            return RuleResult.error("check 结果非布尔: " + raw, touched, params);
        }
        return needsReview
                ? new RuleResult(conclusion, touched, params, null, true)
                : RuleResult.of(conclusion, touched, params);
    }

    /** 求值表达式（编译缓存 + 50ms 超时隔离）。失败返回 {@link EvalFailure}。 */
    Object evalExpr(String ruleId, String expression, JsonNode model, Map<String, Object> cfg) {
        try {
            CelRuntime.Program program = programFor(ruleId, expression);
            Map<String, Object> activation = new java.util.HashMap<>(ModelValueProvider.toCelVariable(model));
            activation.put("cfg", ModelValueProvider.normalizeCfg(cfg));
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
                return new EvalFailure("cel_timeout(" + evalTimeoutMs + "ms)");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return new EvalFailure(cause.getMessage() == null ? cause.toString() : cause.getMessage());
            }
        } catch (Exception e) {
            return new EvalFailure(e.getMessage() == null ? e.toString() : e.getMessage());
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

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RuleResult.Conclusion onBlankOf(RuleSpec rule) {
        String onBlank = rule.onBlank();
        if (onBlank == null || onBlank.isBlank()) {
            return RuleResult.Conclusion.NOT_SATISFIED;
        }
        try {
            return RuleResult.Conclusion.valueOf(onBlank.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RuleResult.Conclusion.NOT_SATISFIED;
        }
    }

    private static List<RuleResult.Param> toParams(RuleSpec rule, Map<String, Object> cfg, String configVersion) {
        List<RuleResult.Param> out = new ArrayList<>();
        for (String name : rule.params()) {
            out.add(new RuleResult.Param(name, cfg == null ? null : cfg.get(name), configVersion));
        }
        return out;
    }

    private static ModelPathResolver.ValueStatus statusOfPath(JsonNode model, String path) {
        try {
            JsonNode node = ModelPathResolver.resolve(model, path);
            if (node == null) {
                return ModelPathResolver.ValueStatus.UNKNOWN;
            }
            return ModelPathResolver.statusOf(node);
        } catch (RuntimeException e) {
            log.warn("requires 路径解析失败 path={}: {}", path, e.toString());
            return ModelPathResolver.ValueStatus.UNKNOWN;
        }
    }

    private static void collectTouched(List<RuleResult.Touched> touched, JsonNode model, List<String> paths) {
        Set<String> seen = new LinkedHashSet<>();
        for (RuleResult.Touched t : touched) {
            seen.add(t.path());
        }
        for (String path : paths) {
            if (!seen.add(path)) {
                continue;
            }
            Map<String, Object> resolved = ModelValueProvider.touchedValue(model, path);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources =
                    (List<Map<String, Object>>) resolved.getOrDefault("sources", List.of());
            touched.add(new RuleResult.Touched(path, resolved.get("value"),
                    String.valueOf(resolved.get("status")), sources));
        }
    }

    /** AST 静态提取以 m 为根的选择链（含函数接收者与下标）；编译失败返回空。 */
    private static List<String> touchedPaths(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        try {
            dev.cel.common.CelValidationResult result = compilerForTouched.compile(expression);
            if (result.hasError()) {
                return List.of();
            }
            Set<String> paths = new LinkedHashSet<>();
            collect(result.getAst().getExpr(), paths);
            return List.copyOf(paths);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final CelCompiler compilerForTouched = CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("m", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("cfg", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addLibraries(CelOptionalLibrary.INSTANCE)
            .build();

    private static void collect(CelExpr expr, Set<String> out) {
        if (expr == null) {
            return;
        }
        String chain = mChainOf(expr);
        if (chain != null && chain.startsWith("m") && chain.length() > 1) {
            // 链与全部前缀（m.a['b'].c → m.a['b'].c / m.a['b'] / m.a / m）
            String[] parts = chain.split("(?=\\.)");
            StringBuilder acc = new StringBuilder();
            for (String part : parts) {
                acc.append(part);
                out.add(acc.toString());
            }
            return;
        }
        for (CelExpr child : childrenOf(expr)) {
            collect(child, out);
        }
    }

    /** 以 m 为根的选择链（{@code m.a['b'].c} 形态）；非 m 根返回 null。 */
    private static String mChainOf(CelExpr expr) {
        if (expr == null) {
            return null;
        }
        switch (expr.getKind()) {
            case IDENT -> {
                return "m".equals(expr.ident().name()) ? "m" : null;
            }
            case SELECT -> {
                String base = mChainOf(expr.select().operand());
                return base == null ? null : base + "." + expr.select().field();
            }
            case CALL -> {
                CelExpr.CelCall call = expr.call();
                if ("_[_]".equals(call.function())) {
                    // 解析态：target=m.a、args=[key]；checked 态：target 缺失、args=[m.a, key]
                    // （cel-java 0.9.1 checked AST 探针实证两种形态）
                    CelExpr base = call.target().orElse(null);
                    CelExpr key = null;
                    if (base == null && call.args().size() == 2) {
                        base = call.args().get(0);
                        key = call.args().get(1);
                    } else if (call.args().size() == 1) {
                        key = call.args().get(0);
                    }
                    String b = mChainOf(base);
                    String k = mChainOf(key);
                    return b != null && k != null && k.startsWith("[") ? b + k : null;
                }
                return null;
            }
            case CONSTANT -> {
                if (expr.constant().getKind() == dev.cel.common.ast.CelConstant.Kind.STRING_VALUE) {
                    return "['" + expr.constant().stringValue() + "']";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static List<CelExpr> childrenOf(CelExpr expr) {
        List<CelExpr> out = new ArrayList<>();
        switch (expr.getKind()) {
            case SELECT -> out.add(expr.select().operand());
            case CALL -> {
                CelExpr.CelCall call = expr.call();
                call.target().ifPresent(out::add);
                out.addAll(call.args());
            }
            case LIST -> out.addAll(expr.list().elements());
            case MAP -> {
                for (CelExpr.CelMap.Entry entry : expr.map().entries()) {
                    out.add(entry.key());
                    out.add(entry.value());
                }
            }
            case COMPREHENSION -> {
                var c = expr.comprehension();
                if (c.iterRange() != null) {
                    out.add(c.iterRange());
                }
                if (c.accuInit() != null) {
                    out.add(c.accuInit());
                }
                if (c.loopCondition() != null) {
                    out.add(c.loopCondition());
                }
                if (c.result() != null) {
                    out.add(c.result());
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** 领域包函数以 DYN 全局重载暴露（含成员调用形态：接收者作为第一参）。 */
    private static List<CelFunctionDecl> packFunctionDeclarations(List<CelFunction> packFunctions) {
        List<CelFunctionDecl> out = new ArrayList<>();
        for (CelFunction f : packFunctions) {
            if (f == null || f.name() == null || f.name().isBlank() || f.implementation() == null) {
                continue;
            }
            out.add(CelFunctionDecl.newFunctionDeclaration(
                    f.name(),
                    CelOverloadDecl.newGlobalOverload(
                            "tioka_pack_" + f.name(), SimpleType.DYN, SimpleType.DYN),
                    // 成员调用形态 x.f()：编译期需要 member 重载（错误形如 applied to 'dyn.()'），
                    // 运行期仍按全局 1 参（接收者为首参）走同一 overloadId 分派
                    CelOverloadDecl.newMemberOverload(
                            "tioka_pack_" + f.name(), SimpleType.DYN, SimpleType.DYN),
                    CelOverloadDecl.newGlobalOverload(
                            "tioka_pack_" + f.name() + "_0", SimpleType.DYN)));
        }
        return out;
    }

    private static List<CelRuntime.CelFunctionBinding> packFunctionBindings(List<CelFunction> packFunctions) {
        List<CelRuntime.CelFunctionBinding> out = new ArrayList<>();
        for (CelFunction f : packFunctions) {
            if (f == null || f.name() == null || f.name().isBlank() || f.implementation() == null) {
                continue;
            }
            String overloadId = "tioka_pack_" + f.name();
            // cel-java 0.9.1 探针实证：同一 overloadId 多绑定仅首个生效，且运行期按参数实际类
            // canHandle(paramClass.isAssignableFrom(arg.getClass())) 分派——Object.class 恒真，
            // 故以单 Object 绑定兜底（Map / List / Optional / Long 等全形态），实现内部自行判型
            out.add(CelRuntime.CelFunctionBinding.from(
                    overloadId, Object.class, arg -> f.implementation().apply(arg)));
            out.add(CelRuntime.CelFunctionBinding.from(
                    overloadId + "_0", List.of(),
                    (dev.cel.runtime.CelFunctionOverload) args -> f.implementation().apply(null)));
        }
        return out;
    }

    /** 求值失败（超时 / 编译 / 运行错误）。 */
    record EvalFailure(String message) {
    }

    public void evict(String ruleId) {
        cache.keySet().removeIf(k -> k.startsWith(ruleId + ":"));
    }
}
