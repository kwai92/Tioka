package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.model.api.constraint.EmitOutputToolConstraintParams;
import com.tioka.model.api.constraint.JsonObjectConstraintParams;
import com.tioka.model.api.constraint.JsonSchemaConstraintParams;
import com.tioka.model.api.constraint.StructuredConstraintRequest;
import com.tioka.model.api.profile.ModelProfile;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-02 / M3-03 §5：三种能力各自生成正确的约束请求形态。
 */
class StructuredRequestBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StructuredRequestBuilder builder;
    private JsonNode authoritativeSchema;

    @BeforeEach
    void setUp() {
        builder = new StructuredRequestBuilder(mapper);
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("name");
        ObjectNode props = schema.putObject("properties");
        ObjectNode name = props.putObject("name");
        name.put("type", "string");
        name.put("minLength", 1);
        name.put("description", "名称");
        name.put("x-tioka-path", "m.name");
        authoritativeSchema = schema;
    }

    @Test
    void schemaModeProducesJsonSchemaResponseFormat() {
        ModelProfile profile = profileWith(Set.of(StructuredOutputMode.STRICT_SCHEMA), Set.of(
                "type", "properties", "required", "additionalProperties", "description", "minLength"));

        StructuredConstraintRequest req = builder.build(profile, authoritativeSchema, false);

        assertEquals(StructuredOutputMode.STRICT_SCHEMA, req.selectedMode());
        assertNotNull(req.responseFormat());
        assertEquals("json_schema", req.responseFormat().get("type"));
        JsonSchemaConstraintParams params = req.asJsonSchema().orElseThrow();
        assertEquals("tioka_output", params.name());
        assertTrue(params.strict());
        assertFalse(params.schema().isEmpty());
        assertNull(req.systemPromptSuffix());
        assertTrue(req.tools() == null || req.tools().isEmpty());
        assertFalse(req.dialectSchema().has("x-tioka-path"));
        assertTrue(req.dialectSchema().path("properties").path("name").has("minLength"));
    }

    @Test
    void jsonModeProducesJsonObjectAndPromptWithSchema() {
        ModelProfile profile = profileWith(Set.of(StructuredOutputMode.JSON_OBJECT), Set.of());

        StructuredConstraintRequest req = builder.build(profile, authoritativeSchema, false);

        assertEquals(StructuredOutputMode.JSON_OBJECT, req.selectedMode());
        assertNotNull(req.responseFormat());
        assertEquals("json_object", req.responseFormat().get("type"));
        JsonObjectConstraintParams params = req.asJsonObject().orElseThrow();
        assertTrue(params.requireJsonKeyword());
        assertTrue(params.systemPromptSuffix().contains("\"type\""));
        assertTrue(req.tools() == null || req.tools().isEmpty());
    }

    @Test
    void toolModeProducesEmitOutputTool() {
        ModelProfile profile = profileWith(Set.of(StructuredOutputMode.TOOL), Set.of(
                "type", "properties", "required", "additionalProperties", "description"));

        StructuredConstraintRequest req = builder.build(profile, authoritativeSchema, false);

        assertEquals(StructuredOutputMode.TOOL, req.selectedMode());
        assertNull(req.responseFormat());
        EmitOutputToolConstraintParams params = req.asEmitOutputTool().orElseThrow();
        assertEquals("emit_output", params.toolName());
        assertFalse(params.strict());
        assertNotNull(params.parameters());
    }

    @Test
    void dialectStripsUnsupportedKeywords() {
        ModelProfile profile = profileWith(
                Set.of(StructuredOutputMode.STRICT_SCHEMA),
                Set.of("type", "properties", "required", "additionalProperties", "description"));

        StructuredConstraintRequest req = builder.build(profile, authoritativeSchema, false);

        JsonNode name = req.dialectSchema().path("properties").path("name");
        assertFalse(name.has("minLength"), "不支持的关键字应被编译掉");
        assertTrue(name.path("description").asText().contains("长度"));
    }

    @Test
    void prefersStrictSchemaOverJsonAndTool() {
        ModelProfile profile = profileWith(
                Set.of(StructuredOutputMode.STRICT_SCHEMA, StructuredOutputMode.JSON_OBJECT, StructuredOutputMode.TOOL),
                Set.of("type", "properties", "required", "additionalProperties", "description"));

        StructuredConstraintRequest req = builder.build(profile, authoritativeSchema, false);

        assertEquals(StructuredOutputMode.STRICT_SCHEMA, req.selectedMode());
    }

    @Test
    void multimodalDowngradeSkipsStrictSchema() {
        StructuredOutputCapability cap = new StructuredOutputCapability(
                Set.of(StructuredOutputMode.STRICT_SCHEMA, StructuredOutputMode.JSON_OBJECT),
                Set.of("type", "properties", "required", "additionalProperties", "description"),
                null,
                Map.of(),
                true,
                true,
                StructuredOutputCapability.MaxTokensAdvice.UNSET,
                StructuredOutputCapability.Stability.GA,
                null,
                null,
                null,
                null);
        ModelProfile profile = new ModelProfile("demo", cap);

        StructuredConstraintRequest req = builder.build(profile, authoritativeSchema, true);

        assertEquals(StructuredOutputMode.JSON_OBJECT, req.selectedMode());
    }

    private static ModelProfile profileWith(Set<StructuredOutputMode> modes, Set<String> keywords) {
        return new ModelProfile(
                "demo",
                new StructuredOutputCapability(
                        modes,
                        keywords,
                        null,
                        Map.of(),
                        false,
                        modes.contains(StructuredOutputMode.JSON_OBJECT),
                        StructuredOutputCapability.MaxTokensAdvice.UNSET,
                        StructuredOutputCapability.Stability.GA,
                        null,
                        null,
                        null,
                        null));
    }
}
