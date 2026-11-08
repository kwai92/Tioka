package com.tioka.model.api.constraint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.profile.StructuredOutputMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredConstraintRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsTypedParamsForThreeModes() {
        Map<String, Object> schemaBody = Map.of("type", "object");
        StructuredConstraintRequest schemaReq = new StructuredConstraintRequest(
                StructuredOutputMode.STRICT_SCHEMA,
                Map.of("type", "json_schema", "json_schema", Map.of(
                        "name", "tioka_output", "strict", true, "schema", schemaBody)),
                null,
                List.of(),
                mapper.nullNode(),
                "h1");
        assertEquals("tioka_output", schemaReq.asJsonSchema().orElseThrow().name());
        assertTrue(schemaReq.asJsonSchema().orElseThrow().strict());
        assertTrue(schemaReq.asJsonObject().isEmpty());
        assertTrue(schemaReq.asEmitOutputTool().isEmpty());

        StructuredConstraintRequest jsonReq = new StructuredConstraintRequest(
                StructuredOutputMode.JSON_OBJECT,
                Map.of("type", "json_object"),
                "请仅输出合法 JSON 对象\nSchema: {}",
                List.of(),
                mapper.nullNode(),
                "h2");
        assertTrue(jsonReq.asJsonObject().orElseThrow().requireJsonKeyword());
        assertTrue(jsonReq.asJsonSchema().isEmpty());

        StructuredConstraintRequest toolReq = new StructuredConstraintRequest(
                StructuredOutputMode.TOOL,
                null,
                null,
                List.of(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "emit_output",
                                "parameters", schemaBody))),
                mapper.nullNode(),
                "h3");
        EmitOutputToolConstraintParams tool = toolReq.asEmitOutputTool().orElseThrow();
        assertEquals("emit_output", tool.toolName());
        assertFalse(tool.strict());
        assertTrue(toolReq.asJsonSchema().isEmpty());
    }
}
