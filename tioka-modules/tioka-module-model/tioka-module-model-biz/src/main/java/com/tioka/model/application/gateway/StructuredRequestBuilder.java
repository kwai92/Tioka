package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.model.api.constraint.StructuredConstraintRequest;
import com.tioka.model.api.profile.ModelProfile;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputMode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方言编译器 + 方式选择（ADR-066 §2 / §4.1）。
 *
 * <p>编译结果按（权威 Schema 内容哈希、能力记录版本、方式）缓存并记 {@code dialectHash}：
 * 同一（Schema、模型）无论在一个 Turn 内还是跨 Turn 都得到相同的方言文本，
 * 保证 ADR-049 的提示缓存与供应商的文法编译缓存都能命中（ADR-066 §2）。
 *
 * <p>编译失败（Schema 超出该模型能力上限）抛 {@link DialectCompileException}；
 * 发布期由 {@link #tryCompile} 静默探测（不抛）。
 */
@Component
public class StructuredRequestBuilder {

    private static final List<StructuredOutputMode> SELECTION_ORDER = List.of(
            StructuredOutputMode.STRICT_SCHEMA,
            StructuredOutputMode.STRICT_TOOL,
            StructuredOutputMode.JSON_OBJECT,
            StructuredOutputMode.TOOL,
            StructuredOutputMode.PROMPT);

    private static final Set<String> ALWAYS_STRIP = Set.of(
            "x-tioka-path",
            "x-tioka-evidence",
            "x-tioka-compare",
            "x-tioka-identity",
            "x-tioka-rules",
            "x-tioka-ref",
            "x-tioka-fallback",
            "x-tioka-coerce",
            "x-tioka-reasoning",
            "x-tioka-group",
            "x-tioka-enum-doc",
            "x-tioka-open-enum",
            "x-tioka-tier");

    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "pattern", "format", "multipleOf");

    /** 方言缓存上限（防无界增长）；超出后不再新增，仅按需重编译。 */
    private static final int MAX_CACHE = 512;

    private final ObjectMapper mapper;
    private final Map<String, Dialect> cache = new ConcurrentHashMap<>();

    public StructuredRequestBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param multimodalInput 本次是否含多模态输入；为 true 且能力声明 multimodalDowngrade 时跳过 STRICT_SCHEMA
     */
    public StructuredConstraintRequest build(ModelProfile profile, JsonNode authoritativeSchema, boolean multimodalInput) {
        if (profile == null) {
            throw new IllegalArgumentException("profile required");
        }
        if (authoritativeSchema == null || authoritativeSchema.isNull()) {
            throw new IllegalArgumentException("authoritativeSchema required");
        }
        StructuredOutputCapability cap = profile.structuredOutput();
        StructuredOutputMode mode = selectMode(cap, multimodalInput);
        if (mode == null) {
            throw new DialectCompileException("NO_ELIGIBLE_MODEL",
                    "no eligible structured output mode for " + profile.modelRef());
        }
        Dialect dialect = dialectFor(authoritativeSchema, cap, mode);
        return switch (mode) {
            case STRICT_SCHEMA -> schemaRequest(mode, dialect.schema(), dialect.hash());
            case JSON_OBJECT, PROMPT -> jsonRequest(mode, dialect.schema(), dialect.hash(), cap.promptKeyword());
            case STRICT_TOOL, TOOL -> toolRequest(mode, dialect.schema(), dialect.hash());
        };
    }

    /**
     * 发布期试编译（ADR-066 §2）：编译失败不抛异常，返回带原因码的结果。
     */
    public CompileOutcome tryCompile(ModelProfile profile, JsonNode authoritativeSchema, boolean multimodalInput) {
        if (profile == null || authoritativeSchema == null || authoritativeSchema.isNull()) {
            return CompileOutcome.failed(null, "SCHEMA_INVALID", "profile 与 authoritativeSchema 必填");
        }
        StructuredOutputCapability cap = profile.structuredOutput();
        StructuredOutputMode mode = selectMode(cap, multimodalInput);
        if (mode == null) {
            return CompileOutcome.failed(null, "NO_ELIGIBLE_MODEL",
                    "no eligible structured output mode for " + profile.modelRef());
        }
        try {
            Dialect dialect = dialectFor(authoritativeSchema, cap, mode);
            return CompileOutcome.ok(mode, dialect.hash());
        } catch (DialectCompileException e) {
            return CompileOutcome.failed(mode, e.reasonCode(), e.getMessage());
        } catch (RuntimeException e) {
            return CompileOutcome.failed(mode, "INTERNAL", e.getMessage());
        }
    }

    private StructuredOutputMode selectMode(StructuredOutputCapability cap, boolean multimodalInput) {
        Set<StructuredOutputMode> available = cap.modes();
        for (StructuredOutputMode candidate : SELECTION_ORDER) {
            if (!available.contains(candidate)) {
                continue;
            }
            if (candidate == StructuredOutputMode.STRICT_SCHEMA
                    && multimodalInput
                    && cap.multimodalDowngrade()) {
                continue;
            }
            // BETA 方式的空间级开关与一致性测试摘除留给 SCH-10 / MH；此处只做能力集合选择
            return candidate;
        }
        return null;
    }

    /** 缓存命中返回同一方言实例（同 Schema、同能力版本、同方式）。 */
    private Dialect dialectFor(JsonNode authoritative, StructuredOutputCapability cap, StructuredOutputMode mode) {
        String key = hash(authoritative) + '|' + capabilityVersion(cap) + '|' + mode.name();
        Dialect cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        JsonNode dialect = compileDialect(authoritative, cap, mode);
        enforceLimits(dialect, cap);
        Dialect computed = new Dialect(dialect, hash(dialect));
        if (cache.size() < MAX_CACHE) {
            cache.put(key, computed);
        }
        return computed;
    }

    private StructuredConstraintRequest schemaRequest(StructuredOutputMode mode, JsonNode dialect, String hash) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "tioka_output");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", mapper.convertValue(dialect, Map.class));
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);
        return new StructuredConstraintRequest(mode, responseFormat, null, List.of(), dialect, hash);
    }

    private StructuredConstraintRequest jsonRequest(
            StructuredOutputMode mode, JsonNode dialect, String hash, boolean requireJsonKeyword) {
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_object");
        String schemaText;
        try {
            schemaText = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dialect);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize dialect schema", e);
        }
        StringBuilder prompt = new StringBuilder();
        if (requireJsonKeyword) {
            prompt.append("请仅输出合法 JSON 对象，不要 Markdown 代码围栏。\n");
        } else {
            prompt.append("请仅输出符合下列 Schema 的对象。\n");
        }
        prompt.append("Schema:\n").append(schemaText);
        return new StructuredConstraintRequest(mode, responseFormat, prompt.toString(), List.of(), dialect, hash);
    }

    private StructuredConstraintRequest toolRequest(StructuredOutputMode mode, JsonNode dialect, String hash) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "emit_output");
        function.put("description", "提交符合契约的结构化输出");
        function.put("parameters", mapper.convertValue(dialect, Map.class));
        if (mode == StructuredOutputMode.STRICT_TOOL) {
            function.put("strict", true);
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return new StructuredConstraintRequest(mode, null, null, List.of(tool), dialect, hash);
    }

    JsonNode compileDialect(JsonNode authoritative, StructuredOutputCapability cap, StructuredOutputMode mode) {
        JsonNode copy = authoritative.deepCopy();
        Set<String> allowed = cap.keywords();
        boolean stripConstraints = mode == StructuredOutputMode.STRICT_SCHEMA
                || mode == StructuredOutputMode.STRICT_TOOL;
        walk(copy, allowed, stripConstraints);
        return copy;
    }

    private void walk(JsonNode node, Set<String> allowedKeywords, boolean stripConstraints) {
        if (node == null || !node.isObject()) {
            if (node != null && node.isArray()) {
                for (JsonNode child : node) {
                    walk(child, allowedKeywords, stripConstraints);
                }
            }
            return;
        }
        ObjectNode obj = (ObjectNode) node;
        List<String> toRemove = new ArrayList<>();
        Iterator<String> names = obj.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (ALWAYS_STRIP.contains(name)) {
                toRemove.add(name);
                continue;
            }
            if (stripConstraints
                    && CONSTRAINT_KEYWORDS.contains(name)
                    && !allowedKeywords.isEmpty()
                    && !allowedKeywords.contains(name)) {
                rewriteConstraintIntoDescription(obj, name);
                toRemove.add(name);
            }
        }
        for (String name : toRemove) {
            obj.remove(name);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
            walk(fields.next().getValue(), allowedKeywords, stripConstraints);
        }
    }

    private void rewriteConstraintIntoDescription(ObjectNode obj, String keyword) {
        JsonNode value = obj.get(keyword);
        if (value == null) {
            return;
        }
        String hint = switch (keyword) {
            case "minLength" -> "长度至少 " + value.asText() + " 字";
            case "maxLength" -> "长度至多 " + value.asText() + " 字";
            case "minItems" -> "至少 " + value.asText() + " 项";
            case "maxItems" -> "至多 " + value.asText() + " 项";
            case "minimum" -> "最小值 " + value.asText();
            case "maximum" -> "最大值 " + value.asText();
            case "pattern" -> "须匹配模式 " + value.asText();
            case "format" -> "格式为 " + value.asText();
            default -> keyword + "=" + value.asText();
        };
        String existing = obj.path("description").asText("");
        if (existing.isBlank()) {
            obj.put("description", hint);
        } else if (!existing.contains(hint)) {
            obj.put("description", existing + "；" + hint);
        }
    }

    // ==================== 能力上限（ADR-066 §3 S4 的模型侧口径） ====================

    /** 编译后按能力记录的上限校验；超限即编译失败（该模型不适用于此 Schema）。 */
    private void enforceLimits(JsonNode dialect, StructuredOutputCapability cap) {
        StructuredOutputCapability.Limits limits = cap.limits();
        if (limits == null) {
            return;
        }
        Stats stats = new Stats();
        collect(dialect, 1, stats);
        if (limits.maxDepth() != null && stats.depth > limits.maxDepth()) {
            throw new DialectCompileException("DIALECT_TOO_DEEP",
                    "方言嵌套深度 " + stats.depth + " 超过模型上限 " + limits.maxDepth());
        }
        if (limits.maxProperties() != null && stats.properties > limits.maxProperties()) {
            throw new DialectCompileException("DIALECT_TOO_MANY_PROPERTIES",
                    "方言属性总数 " + stats.properties + " 超过模型上限 " + limits.maxProperties());
        }
        if (limits.maxEnumSize() != null && stats.maxEnum > limits.maxEnumSize()) {
            throw new DialectCompileException("DIALECT_ENUM_TOO_LARGE",
                    "方言枚举项数 " + stats.maxEnum + " 超过模型上限 " + limits.maxEnumSize());
        }
        if (limits.maxUnionTypes() != null && stats.unions > limits.maxUnionTypes()) {
            throw new DialectCompileException("DIALECT_TOO_MANY_UNIONS",
                    "方言联合/可空字段数 " + stats.unions + " 超过模型上限 " + limits.maxUnionTypes());
        }
    }

    private static final class Stats {
        int depth;
        int properties;
        int maxEnum;
        int unions;
    }

    private static void collect(JsonNode node, int depth, Stats stats) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, depth, stats);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        stats.depth = Math.max(stats.depth, depth);
        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                JsonNode child = fields.next().getValue();
                stats.properties++;
                if (isUnionOrNullable(child)) {
                    stats.unions++;
                }
                JsonNode en = child == null ? null : child.get("enum");
                if (en != null && en.isArray()) {
                    stats.maxEnum = Math.max(stats.maxEnum, en.size());
                }
                collect(child, depth + 1, stats);
            }
        }
        JsonNode items = node.get("items");
        if (items != null) {
            collect(items, depth + 1, stats);
        }
        JsonNode oneOf = node.get("oneOf");
        if (oneOf != null && oneOf.isArray()) {
            for (JsonNode child : oneOf) {
                collect(child, depth + 1, stats);
            }
        }
        JsonNode ap = node.get("additionalProperties");
        if (ap != null && ap.isObject()) {
            collect(ap, depth + 1, stats);
        }
    }

    private static boolean isUnionOrNullable(JsonNode prop) {
        if (prop == null) {
            return false;
        }
        if (prop.has("oneOf")) {
            return true;
        }
        JsonNode type = prop.get("type");
        if (type != null && type.isArray()) {
            for (JsonNode t : type) {
                if ("null".equals(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 哈希 ====================

    private String hash(JsonNode node) {
        try {
            return sha256(mapper.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new IllegalStateException("dialect hash failed", e);
        }
    }

    /** 能力记录版本指纹：记录内容变化即视为新版本（ADR-066 §2 的「能力记录版本」）。 */
    static String capabilityVersion(StructuredOutputCapability cap) {
        return sha256(cap.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    private record Dialect(JsonNode schema, String hash) {
    }

    /** 编译结果（发布期试编译用，不抛异常）。 */
    public record CompileOutcome(boolean compiled, StructuredOutputMode mode,
                                 String dialectHash, String reasonCode, String message) {
        static CompileOutcome ok(StructuredOutputMode mode, String dialectHash) {
            return new CompileOutcome(true, mode, dialectHash, null, null);
        }

        static CompileOutcome failed(StructuredOutputMode mode, String reasonCode, String message) {
            return new CompileOutcome(false, mode, null, reasonCode, message);
        }
    }

    /** 方言编译失败（Schema 超出该模型能力）。 */
    public static final class DialectCompileException extends IllegalStateException {
        private final String reasonCode;

        public DialectCompileException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
