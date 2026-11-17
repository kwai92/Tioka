package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.ReasonCode;
import com.tioka.ontology.api.schema.SchemaError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRuleEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaRuleEvaluator evaluator = new SchemaRuleEvaluator();

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
              "required": ["startDate", "endDate"],
              "properties": {
                "startDate": {"type": "string", "description": "起租日"},
                "endDate": {"type": "string", "description": "到期日"}
              },
              "x-tioka-rules": [
                {"id": "lease.period", "expr": "self.endDate >= self.startDate",
                 "message": "到期日不早于起租日", "severity": "ERROR", "hint": true}
              ]
            }
            """;

    @Test
    void collectFindsRules() {
        List<SchemaRule> rules = SchemaRuleEvaluator.collect(json(SCHEMA));
        assertEquals(1, rules.size());
        assertEquals("lease.period", rules.get(0).id());
        assertTrue(rules.get(0).hint());
    }

    @Test
    void compileAllOkForValidExpression() {
        assertTrue(evaluator.compileAll(json(SCHEMA)).isEmpty());
    }

    @Test
    void compileAllReportsBrokenExpression() {
        JsonNode schema = json("""
                {"type":"object","x-tioka-rules":[{"id":"bad","expr":"self. == ","message":"x"}]}
                """);
        List<SchemaError> issues = evaluator.compileAll(schema);
        assertFalse(issues.isEmpty());
        assertEquals("RULE_COMPILE", issues.get(0).code());
    }

    @Test
    void ruleViolationIsReportedAsRule() {
        JsonNode payload = json("""
                {"startDate":"2026-01-01","endDate":"2025-01-01"}
                """);
        List<SchemaError> errors = evaluator.evaluateErrors(json(SCHEMA), payload);
        assertEquals(1, errors.size());
        assertEquals(ReasonCode.RULE.name(), errors.get(0).code());
        assertEquals("到期日不早于起租日", errors.get(0).message());
        assertTrue(evaluator.warnedRules(json(SCHEMA), payload).isEmpty());
    }

    @Test
    void satisfiedRuleHasNoErrors() {
        JsonNode payload = json("""
                {"startDate":"2026-01-01","endDate":"2027-01-01"}
                """);
        assertTrue(evaluator.evaluateErrors(json(SCHEMA), payload).isEmpty());
    }

    @Test
    void warnSeverityDoesNotBlock() {
        JsonNode schema = json("""
                {"type":"object","x-tioka-rules":[
                   {"id":"w","expr":"self.a > self.b","message":"warn","severity":"WARN"}]}
                """);
        JsonNode payload = json("{\"a\": 1, \"b\": 5}");
        assertTrue(evaluator.evaluateErrors(schema, payload).isEmpty());
        assertEquals(1, evaluator.warnedRules(schema, payload).size());
    }

    @Test
    void rootVariableAccessibleFromNestedObject() {
        JsonNode schema = json("""
                {"type":"object","properties":{"items":{"type":"array","items":{
                   "type":"object","x-tioka-rules":[
                     {"id":"sum","expr":"self.amount <= root.limit","message":"超限"}]}}}}
                """);
        JsonNode ok = json("""
                {"limit": 100, "items": [{"amount": 50}]}
                """);
        assertTrue(evaluator.evaluateErrors(schema, ok).isEmpty());
        JsonNode bad = json("""
                {"limit": 100, "items": [{"amount": 500}]}
                """);
        assertEquals(1, evaluator.evaluateErrors(schema, bad).size());
    }
}
