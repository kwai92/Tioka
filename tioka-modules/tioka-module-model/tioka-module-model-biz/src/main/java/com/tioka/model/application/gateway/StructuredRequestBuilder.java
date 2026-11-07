package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.model.api.profile.ModelProfile;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputMode;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方言编译器 + 方式选择（ADR-066 §2 / §4.1）。SCH-02 未接线 Runtime / ModelGateway。
 */
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

    private final ObjectMapper mapper;

    public StructuredRequestBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param multimodalInput 本次是否含多模态输入；为 true 且能力声明 multimodalDowngrade 时跳过 STRICT_SCHEMA
     */
    public StructuredChatRequest build(ModelProfile profile, JsonNode authoritativeSchema, boolean multimodalInput) {
        if (profile == null) {
            throw new IllegalArgumentException("profile required");
        }
        if (authoritativeSchema == null || authoritativeSchema.isNull()) {
            throw new IllegalArgumentException("authoritativeSchema required");
        }
        StructuredOutputCapability cap = profile.structuredOutput();
        StructuredOutputMode mode = selectMode(cap, multimodalInput);
        if (mode == null) {
            throw new IllegalStateException("no eligible structured output mode for " + profile.modelRef());
        }
        JsonNode dialect = compileDialect(authoritativeSchema, cap, mode);
        String hash = hash(dialect);
        return switch (mode) {
            case STRICT_SCHEMA -> schemaRequest(mode, dialect, hash);
            case JSON_OBJECT, PROMPT -> jsonRequest(mode, dialect, hash, cap.promptKeyword());
            case STRICT_TOOL, TOOL -> toolRequest(mode, dialect, hash);
        };
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
            // BETA 方式的空间级开关与一致性测试摘除留给 SCH-06 / MH；此处只做能力集合选择
            return candidate;
        }
        return null;
    }

    private StructuredChatRequest schemaRequest(StructuredOutputMode mode, JsonNode dialect, String hash) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "tioka_output");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", mapper.convertValue(dialect, Map.class));
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);
        return new StructuredChatRequest(mode, responseFormat, null, List.of(), dialect, hash);
    }

    private StructuredChatRequest jsonRequest(
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
        return new StructuredChatRequest(mode, responseFormat, prompt.toString(), List.of(), dialect, hash);
    }

    private StructuredChatRequest toolRequest(StructuredOutputMode mode, JsonNode dialect, String hash) {
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
        return new StructuredChatRequest(mode, null, null, List.of(tool), dialect, hash);
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

    private String hash(JsonNode dialect) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(dialect);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("dialect hash failed", e);
        }
    }
}
