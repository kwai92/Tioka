package com.tioka.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalServiceTest {

    @Test
    void baselineShapeAndRecallNonNegative() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/knowledge/eval-baseline.json")) {
            assertNotNull(in);
            JsonNode baseline = new ObjectMapper().readTree(in);
            assertTrue(baseline.has("kbId"));
            assertTrue(baseline.has("queries"));
            assertTrue(baseline.has("recallAtK"));
            assertTrue(baseline.has("hits"));
        }

        KnowledgeRepository repo = KnowledgeInMemorySupport.repo();
        KnowledgeService knowledge = KnowledgeInMemorySupport.service(repo);
        var kb = knowledge.createBase("ws", "eval");
        knowledge.ingest(kb.id(), "policy", "Our refund policy allows returns within 30 days. Standard shipping time is 3-5 business days.", "upload");

        EvalService eval = new EvalService(knowledge);
        Map<String, Object> report = eval.run(kb.id(), List.of(
                Map.of("query", "refund policy", "expectContains", "refund"),
                Map.of("query", "shipping time", "expectContains", "shipping")
        ));

        assertEquals(kb.id(), report.get("kbId"));
        assertTrue(report.containsKey("queries"));
        assertTrue(report.containsKey("hits"));
        double recall = ((Number) report.get("recallAtK")).doubleValue();
        assertTrue(recall >= 0.0);
        assertTrue(recall <= 1.0);
    }
}
