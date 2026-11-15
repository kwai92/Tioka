package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.ReasonCode;
import com.tioka.ontology.api.schema.SchemaError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefIntegrityCheckerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["clauseRef", "unitRef"],
              "properties": {
                "clauseRef": {"type": "string", "description": "条款", "x-tioka-ref": "doc.clause"},
                "unitRef": {"type": "string", "description": "单元", "x-tioka-ref": "doc.unit"}
              }
            }
            """;

    @Test
    void missingReferencedObjectIsReported() {
        RefIntegrityChecker checker = new RefIntegrityChecker(Map.of(
                "doc.clause", (ref, visible) -> Optional.of(!"C-1".equals(ref)),
                "doc.unit", (ref, visible) -> Optional.of(true)));
        JsonNode payload = json("""
                {"clauseRef": "C-1", "unitRef": "doc:1:0"}
                """);
        List<SchemaError> errors = checker.check(json(SCHEMA), payload, List.of("doc"));
        assertEquals(1, errors.size());
        assertEquals(ReasonCode.REF_NOT_FOUND.name(), errors.get(0).code());
        assertTrue(errors.get(0).pointer().contains("clauseRef"));
    }

    @Test
    void allResolvableHasNoErrors() {
        RefIntegrityChecker checker = new RefIntegrityChecker(Map.of(
                "doc.clause", (ref, visible) -> Optional.of(true),
                "doc.unit", (ref, visible) -> Optional.of(true)));
        JsonNode payload = json("""
                {"clauseRef": "C-2", "unitRef": "doc:1:0"}
                """);
        assertTrue(checker.check(json(SCHEMA), payload, List.of("doc")).isEmpty());
    }

    @Test
    void absentResolverSkipsType() {
        // 只有 doc.clause 解析器；doc.unit 无解析器 → 跳过而不报错
        RefIntegrityChecker checker = new RefIntegrityChecker(Map.of(
                "doc.clause", (ref, visible) -> Optional.of(true)));
        JsonNode payload = json("""
                {"clauseRef": "C-3", "unitRef": "missing:1:0"}
                """);
        assertTrue(checker.check(json(SCHEMA), payload, List.of("doc")).isEmpty());
    }

    @Test
    void emptyResolversSkipEverything() {
        RefIntegrityChecker checker = new RefIntegrityChecker(Map.of());
        JsonNode payload = json("""
                {"clauseRef": "x", "unitRef": "y"}
                """);
        assertTrue(checker.check(json(SCHEMA), payload, List.of()).isEmpty());
    }

    @Test
    void arrayOfRefsCheckedPerElement() {
        JsonNode schema = json("""
                {"type":"object","properties":{"clauses":{"type":"array",
                  "items":{"type":"string","x-tioka-ref":"doc.clause"}}}}
                """);
        RefIntegrityChecker checker = new RefIntegrityChecker(Map.of(
                "doc.clause", (ref, visible) -> Optional.of(!"C-9".equals(ref))));
        JsonNode payload = json("""
                {"clauses": ["C-1", "C-9"]}
                """);
        List<SchemaError> errors = checker.check(schema, payload, List.of());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).pointer().endsWith("/1"));
    }

    @Test
    void knownTypesCoverAdrList() {
        assertEquals(Set.of("doc.unit", "doc.clause", "kb.chunk", "model.path", "dataitem"),
                RefIntegrityChecker.KNOWN_TYPES);
    }
}
