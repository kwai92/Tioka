package com.tioka.eval.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-16（KNW-07）：MrrEvaluator 纯逻辑测试（离线，同 EvaluatorSpiIT 风格）。
 */
class MrrEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MrrEvaluator evaluator = new MrrEvaluator();

    @Test
    void firstPositionGivesOne() {
        EvalScore s = evaluator.score(subject(
                "{\"references\":[\"doc_a\"]}",
                "{\"hits\":[{\"docId\":\"doc_a\"},{\"docId\":\"doc_b\"}]}"),
                Map.of());
        assertEquals(1.0, s.score());
        assertTrue(s.passed());
    }

    @Test
    void rankGivesReciprocal() {
        EvalScore s = evaluator.score(subject(
                "{\"references\":[\"doc_b\"]}",
                "{\"hits\":[{\"docId\":\"doc_a\"},{\"docId\":\"doc_b\"},{\"docId\":\"doc_c\"}]}"),
                Map.of());
        assertEquals(0.5, s.score());
        assertTrue(s.passed());
        assertTrue(s.reason().contains("2"));
    }

    @Test
    void noHitScoresZero() {
        EvalScore s = evaluator.score(subject(
                "{\"references\":[\"doc_z\"]}",
                "{\"hits\":[{\"docId\":\"doc_a\"},{\"docId\":\"doc_b\"}]}"),
                Map.of());
        assertEquals(0.0, s.score());
        assertFalse(s.passed());
    }

    @Test
    void beyondKDoesNotCount() {
        EvalScore s = evaluator.score(subject(
                "{\"references\":[\"doc_b\"]}",
                "{\"hits\":[{\"docId\":\"doc_a\"},{\"docId\":\"doc_x\"},{\"docId\":\"doc_b\"}]}"),
                Map.of("k", 2));
        assertEquals(0.0, s.score());
        assertFalse(s.passed());
    }

    @Test
    void missingHitsOrEmptyReferencesScoreZero() {
        EvalScore noHits = evaluator.score(subject(
                "{\"references\":[\"doc_a\"]}", "null"), Map.of());
        assertEquals(0.0, noHits.score());
        assertFalse(noHits.passed());

        EvalScore emptyRefs = evaluator.score(subject(
                "null", "{\"hits\":[{\"docId\":\"doc_a\"}]}"), Map.of());
        assertEquals(0.0, emptyRefs.score());
        assertFalse(emptyRefs.passed());
    }

    private EvalSubject subject(String expected, String actual) {
        try {
            return new EvalSubject("case_1", mapper.readTree("{\"query\":\"q\"}"),
                    mapper.readTree(expected), mapper.readTree(actual), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
