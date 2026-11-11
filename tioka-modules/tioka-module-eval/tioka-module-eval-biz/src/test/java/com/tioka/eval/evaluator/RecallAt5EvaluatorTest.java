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
 * C-16（KNW-07）：RecallAt5Evaluator 纯逻辑测试（离线，同 EvaluatorSpiIT 风格）。
 */
class RecallAt5EvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecallAt5Evaluator evaluator = new RecallAt5Evaluator();

    @Test
    void fullHitPassesWithScoreOne() {
        EvalScore s = evaluator.score(subject(
                "{\"references\":[\"doc_a\",\"doc_b\"]}",
                "{\"hits\":[{\"docId\":\"doc_b\"},{\"docId\":\"doc_c\"},{\"docId\":\"doc_a\"}]}"),
                Map.of());
        assertEquals(1.0, s.score());
        assertTrue(s.passed());
    }

    @Test
    void partialHitScoresRatioAndFails() {
        EvalScore s = evaluator.score(subject(
                "{\"references\":[\"doc_a\",\"doc_b\"]}",
                "{\"hits\":[{\"docId\":\"doc_b\"},{\"docId\":\"doc_c\"}]}"),
                Map.of());
        assertEquals(0.5, s.score());
        assertFalse(s.passed());
    }

    @Test
    void onlyTopKHitsCount() {
        // k=2：第 3 条之后不计入 → doc_a 在第 3 位不算命中
        EvalScore limited = evaluator.score(subject(
                "{\"references\":[\"doc_a\"]}",
                "{\"hits\":[{\"docId\":\"doc_x\"},{\"docId\":\"doc_y\"},{\"docId\":\"doc_a\"}]}"),
                Map.of("k", 2));
        assertEquals(0.0, limited.score());
        assertFalse(limited.passed());

        // 缺省 k=5：同数据命中
        EvalScore defaultK = evaluator.score(subject(
                "{\"references\":[\"doc_a\"]}",
                "{\"hits\":[{\"docId\":\"doc_x\"},{\"docId\":\"doc_y\"},{\"docId\":\"doc_a\"}]}"),
                Map.of());
        assertEquals(1.0, defaultK.score());
        assertTrue(defaultK.passed());
    }

    @Test
    void missingHitsOrEmptyReferencesScoreZero() {
        EvalScore noHits = evaluator.score(subject(
                "{\"references\":[\"doc_a\"]}", "{\"other\":1}"), Map.of());
        assertEquals(0.0, noHits.score());
        assertFalse(noHits.passed());
        assertTrue(noHits.reason().contains("hits"));

        EvalScore emptyRefs = evaluator.score(subject("null", "{\"hits\":[{\"docId\":\"doc_a\"}]}"), Map.of());
        assertEquals(0.0, emptyRefs.score());
        assertFalse(emptyRefs.passed());
        assertTrue(emptyRefs.reason().contains("references"));
    }

    @Test
    void duplicateHitsAndReferencesDeduplicate() {
        // 同一 docId 重复出现只计一次；references 去重后 1 条
        EvalScore s = evaluator.score(subject(
                "{\"references\":[\"doc_a\",\"doc_a\"]}",
                "{\"hits\":[{\"docId\":\"doc_a\"},{\"docId\":\"doc_a\"}]}"),
                Map.of());
        assertEquals(1.0, s.score());
        assertTrue(s.passed());
    }

    @Test
    void configKAcceptsNumericStringAndFallsBack() {
        assertEquals(3, RecallAt5Evaluator.kOf(Map.of("k", 3)));
        assertEquals(3, RecallAt5Evaluator.kOf(Map.of("k", "3")));
        assertEquals(5, RecallAt5Evaluator.kOf(Map.of("k", "abc")));
        assertEquals(5, RecallAt5Evaluator.kOf(Map.of("k", 0)));
        assertEquals(5, RecallAt5Evaluator.kOf(null));
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
