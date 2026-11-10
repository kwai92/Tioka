package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-03：OutputValidator x-tioka-path 标注字段的取值提取（最小实现）。
 * 「路径存在」检查依赖 ONT-01 路径目录，未接线前跳过。
 */
class OutputValidatorPathTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OutputValidator validator = new OutputValidator();

    @Test
    void extractsAnnotatedValuesAtTopLevelAndInsideArrays() throws Exception {
        JsonNode schema = MAPPER.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "x-tioka-path": true},
                    "name": {"type": "string"},
                    "refs": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "path": {"type": "string", "x-tioka-path": true}
                        }
                      }
                    }
                  }
                }""");
        JsonNode payload = MAPPER.readTree("""
                {"path":"lease.contract.tenant","name":"张三","refs":[{"path":"lease.contract.fee"}]}""");
        List<OutputValidator.PathValue> values = validator.xTiokaPathValues(schema, payload);
        assertEquals(2, values.size());
        assertTrue(values.contains(new OutputValidator.PathValue("/path", "lease.contract.tenant")));
        assertTrue(values.contains(new OutputValidator.PathValue("/refs/0/path", "lease.contract.fee")));
    }

    @Test
    void missingPayloadFieldIsSkipped() throws Exception {
        JsonNode schema = MAPPER.readTree("""
                {"type":"object","properties":{"path":{"type":"string","x-tioka-path":true}}}""");
        JsonNode payload = MAPPER.readTree("{}");
        assertTrue(validator.xTiokaPathValues(schema, payload).isEmpty());
    }

    @Test
    void unannotatedSchemaYieldsNothing() throws Exception {
        JsonNode schema = MAPPER.readTree("""
                {"type":"object","properties":{"path":{"type":"string"}}}""");
        JsonNode payload = MAPPER.readTree(""" 
                {"path":"x"}""".stripIndent());
        assertTrue(validator.xTiokaPathValues(schema, payload).isEmpty());
    }
}
