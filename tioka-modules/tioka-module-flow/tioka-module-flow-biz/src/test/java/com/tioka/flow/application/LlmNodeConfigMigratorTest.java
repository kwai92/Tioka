package com.tioka.flow.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmNodeConfigMigratorTest {

    @Test
    void stripsLegacyDirectFieldsAndKeepsRoutingPlanId() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(Map.of(
                        "id", "n1",
                        "type", "LLM",
                        "config", Map.of(
                                "baseUrl", "https://api.openai.com",
                                "apiKey", "sk-x",
                                "model", "gpt-4",
                                "routingPlanId", "rp_1"))),
                "edges", List.of());

        Map<String, Object> migrated = LlmNodeConfigMigrator.migrate(graph);
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) ((List<Map<String, Object>>) migrated.get("nodes"))
                .get(0).get("config");
        assertEquals("rp_1", cfg.get("routingPlanId"));
        assertFalse(cfg.containsKey("baseUrl"));
        assertFalse(cfg.containsKey("apiKey"));
        assertFalse(cfg.containsKey("model"));
    }

    @Test
    void mapsLegacyOnlyToEmptyRoutingPlanId() {
        Map<String, Object> cfgIn = new LinkedHashMap<>();
        cfgIn.put("baseUrl", "mock://openai");
        cfgIn.put("apiKeyRef", "llm::k");
        cfgIn.put("model", "gpt");
        Map<String, Object> out = LlmNodeConfigMigrator.migrateLlmConfig("n2", cfgIn);
        assertTrue(out.isEmpty() || !out.containsKey("routingPlanId"));
        assertFalse(out.containsKey("baseUrl"));
        assertFalse(out.containsKey("apiKeyRef"));
        assertFalse(out.containsKey("model"));
    }

    @Test
    void leavesNonLlmNodesUntouched() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(Map.of(
                        "id", "h1",
                        "type", "HTTP",
                        "config", Map.of("url", "https://example.com", "apiKeyRef", "http::t"))),
                "edges", List.of());
        Map<String, Object> migrated = LlmNodeConfigMigrator.migrate(graph);
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) ((List<Map<String, Object>>) migrated.get("nodes"))
                .get(0).get("config");
        assertEquals("https://example.com", cfg.get("url"));
        assertEquals("http::t", cfg.get("apiKeyRef"));
    }
}
