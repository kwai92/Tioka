package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaStrictTierValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String VALID = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["basis", "conclusion"],
              "properties": {
                "basis": {"type": "string", "description": "依据", "x-tioka-reasoning": true},
                "conclusion": {
                  "type": "string",
                  "description": "结论",
                  "enum": ["satisfied", "not_satisfied", "pending_review"],
                  "x-tioka-enum-doc": {
                    "satisfied": "满足", "not_satisfied": "不满足", "pending_review": "待复核"
                  },
                  "x-tioka-fallback": "pending_review"
                }
              }
            }
            """;

    @Test
    void validStrictSchemaPasses() {
        assertTrue(SchemaPublishValidator.validateStrictTier(json(VALID), "tioka://schema/demo@1").isEmpty());
    }

    @Test
    void subsetValidatorAcceptsNewExtensions() {
        // ADR-066 §2/§3/§5 新增的 x-tioka-* 扩展必须在白名单内
        assertTrue(SchemaPublishValidator.validate(json(VALID)).isEmpty());
    }

    @Test
    void s1AllPropertiesRequired() {
        JsonNode schema = json("""
                {"type":"object","additionalProperties":false,"required":[],
                 "properties":{"a":{"type":"string","description":"a"}}}
                """);
        assertTrue(hasCode(SchemaPublishValidator.validateStrictTier(schema), "STRICT_S1"));
    }

    @Test
    void s2ClosedObject() {
        JsonNode schema = json("""
                {"type":"object","required":["a"],"properties":{"a":{"type":"string","description":"a"}}}
                """);
        assertTrue(hasCode(SchemaPublishValidator.validateStrictTier(schema), "STRICT_S2"));
    }

    @Test
    void s5PropertyDescriptionRequired() {
        JsonNode schema = json("""
                {"type":"object","additionalProperties":false,"required":["a"],
                 "properties":{"a":{"type":"string"}}}
                """);
        assertTrue(hasCode(SchemaPublishValidator.validateStrictTier(schema), "STRICT_S5"));
    }

    @Test
    void s6ReasoningBeforeConclusion() {
        JsonNode schema = json("""
                {"type":"object","additionalProperties":false,"required":["conclusion","basis"],
                 "properties":{
                   "conclusion":{"type":"string","description":"结论"},
                   "basis":{"type":"string","description":"依据","x-tioka-reasoning":true}}}
                """);
        assertTrue(hasCode(SchemaPublishValidator.validateStrictTier(schema), "STRICT_S6"));
    }

    @Test
    void s7FallbackMustBeDeclaredEnumValue() {
        JsonNode schema = json("""
                {"type":"object","additionalProperties":false,"required":["c"],
                 "properties":{"c":{"type":"string","description":"结论",
                   "enum":["satisfied","not_satisfied"],"x-tioka-enum-doc":{"satisfied":"x","not_satisfied":"y"},
                   "x-tioka-fallback":"pending_review"}}}
                """);
        assertTrue(hasCode(SchemaPublishValidator.validateStrictTier(schema), "STRICT_S7"));
    }

    @Test
    void s9EnumValuesLowercase() {
        JsonNode schema = json("""
                {"type":"object","additionalProperties":false,"required":["c"],
                 "properties":{"c":{"type":"string","description":"结论",
                   "enum":["Satisfied"],"x-tioka-enum-doc":{"Satisfied":"满足"}}}}
                """);
        assertTrue(hasCode(SchemaPublishValidator.validateStrictTier(schema), "STRICT_S9"));
    }

    @Test
    void s3NoRecursion() {
        JsonNode schema = json("""
                {"type":"object","additionalProperties":false,"required":["child"],
                 "properties":{"child":{"$ref":"tioka://schema/demo@1"}}}
                """);
        List<SchemaPublishValidator.SchemaPublishIssue> issues =
                SchemaPublishValidator.validateStrictTier(schema, "tioka://schema/demo@1");
        assertTrue(hasCode(issues, "STRICT_S3"));
    }

    @Test
    void s4DepthLimit() {
        JsonNode schema = json("""
                {"type":"object","additionalProperties":false,"required":["a"],
                 "properties":{"a":{"type":"object","additionalProperties":false,"required":["b"],
                   "properties":{"b":{"type":"object","additionalProperties":false,"required":["c"],
                     "properties":{"c":{"type":"object","additionalProperties":false,"required":["d"],
                       "properties":{"d":{"type":"object","additionalProperties":false,"required":["e"],
                         "properties":{"e":{"type":"string","description":"e"}}}}}}}}}}}
                """);
        assertTrue(hasCode(SchemaPublishValidator.validateStrictTier(schema), "STRICT_S4"));
    }

    @Test
    void relaxedTierSkipsStrictChecks() {
        JsonNode schema = json("""
                {"type":"object","x-tioka-tier":"relaxed","properties":{"a":{"type":"string"}}}
                """);
        assertTrue(SchemaPublishValidator.validateStrictTier(schema).isEmpty());
        assertEquals(SchemaPublishValidator.SchemaTier.RELAXED, SchemaPublishValidator.tierOf(schema));
    }

    private static boolean hasCode(List<SchemaPublishValidator.SchemaPublishIssue> issues, String code) {
        return issues.stream().anyMatch(i -> code.equals(i.code()));
    }
}
