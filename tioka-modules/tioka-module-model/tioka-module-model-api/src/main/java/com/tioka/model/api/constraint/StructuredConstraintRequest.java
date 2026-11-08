package com.tioka.model.api.constraint;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.model.api.profile.StructuredOutputMode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 三种约束生成方式的统一请求形态（ADR-037 / ADR-066 §4.1；SCH-02）。
 * <p>
 * 尚未接入 {@code DefaultModelGateway}（避免持 K1c）；Runtime 接线在 SCH-03。
 */
public record StructuredConstraintRequest(
        StructuredOutputMode selectedMode,
        Map<String, Object> responseFormat,
        String systemPromptSuffix,
        List<Map<String, Object>> tools,
        JsonNode dialectSchema,
        String dialectHash
) {
    public StructuredConstraintRequest {
        if (selectedMode == null) {
            throw new IllegalArgumentException("selectedMode required");
        }
        if (tools == null) {
            tools = List.of();
        } else {
            tools = List.copyOf(tools);
        }
        if (responseFormat != null) {
            responseFormat = Map.copyOf(responseFormat);
        }
    }

    /** SCHEMA / STRICT_SCHEMA。 */
    @SuppressWarnings("unchecked")
    public Optional<JsonSchemaConstraintParams> asJsonSchema() {
        if (selectedMode != StructuredOutputMode.STRICT_SCHEMA || responseFormat == null) {
            return Optional.empty();
        }
        Object raw = responseFormat.get("json_schema");
        if (!(raw instanceof Map<?, ?> jsonSchema)) {
            return Optional.empty();
        }
        String name = jsonSchema.get("name") instanceof String s ? s : "tioka_output";
        boolean strict = Boolean.TRUE.equals(jsonSchema.get("strict"));
        Map<String, Object> schema = jsonSchema.get("schema") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        return Optional.of(new JsonSchemaConstraintParams(name, strict, schema));
    }

    /** JSON / JSON_OBJECT / PROMPT。 */
    public Optional<JsonObjectConstraintParams> asJsonObject() {
        if (selectedMode != StructuredOutputMode.JSON_OBJECT && selectedMode != StructuredOutputMode.PROMPT) {
            return Optional.empty();
        }
        boolean requireJson = systemPromptSuffix != null
                && systemPromptSuffix.toLowerCase().contains("json");
        return Optional.of(new JsonObjectConstraintParams(systemPromptSuffix, requireJson));
    }

    /** TOOL / STRICT_TOOL。 */
    @SuppressWarnings("unchecked")
    public Optional<EmitOutputToolConstraintParams> asEmitOutputTool() {
        if (selectedMode != StructuredOutputMode.TOOL && selectedMode != StructuredOutputMode.STRICT_TOOL) {
            return Optional.empty();
        }
        if (tools == null || tools.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> tool = tools.get(0);
        Object fnRaw = tool.get("function");
        if (!(fnRaw instanceof Map<?, ?> function)) {
            return Optional.empty();
        }
        String name = function.get("name") instanceof String s ? s : "emit_output";
        boolean strict = Boolean.TRUE.equals(function.get("strict"));
        Map<String, Object> parameters = function.get("parameters") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        return Optional.of(new EmitOutputToolConstraintParams(name, strict, parameters));
    }
}
