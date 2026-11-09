package com.tioka.model.application.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.routing.RoutingMode;
import com.tioka.model.domain.ModelEntities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MH-06：路由计划 spec 解析（新结构 + V1 平铺向后兼容口径）。
 */
class RoutingPlanSpecParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesNewModeSpec() throws Exception {
        String json = """
                {
                  "mode": "BRANCH",
                  "groups": {"main": ["bnd_a", "bnd_b"], "free": ["bnd_c"]},
                  "categoryToGroup": {"coding": "main", "general": "free"},
                  "difficultyToGroup": {"HARD": "main"},
                  "decision": {"deciderId": "llm", "bindingId": "bnd_dec", "timeoutMs": 1500, "fallbackGroup": "main"},
                  "escalation": {"freeGroup": "free", "mainGroup": "main"},
                  "preferPreviousInGroup": true
                }
                """;
        RoutingPlanSnapshot s = RoutingPlanSpecParser.parse(mapper.readTree(json));
        assertEquals(RoutingMode.BRANCH, s.mode());
        assertEquals(2, s.groups().size());
        assertEquals("main", s.categoryToGroup().get("coding"));
        assertEquals("main", s.difficultyToGroup().get("HARD"));
        assertEquals("llm", s.decision().deciderId());
        assertEquals(1500, s.decision().timeoutMs());
        assertEquals("main", s.escalation().mainGroup());
        assertTrue(s.preferPreviousInGroup());
        assertEquals("free", s.freeGroup());
        assertEquals("main", s.mainGroup());
    }

    @Test
    void legacyFlatSpecBecomesEquivalentFixed() throws Exception {
        // V151 存量第 1 版 spec（ModelHubSpecs.planSpec 平铺结构）
        String json = """
                {"name": "r", "type": "PRIMARY_FALLBACK", "primaryBindingId": "bnd_a",
                 "fallbackBindingId": "bnd_b", "fallbackChain": "bnd_b,bnd_c"}
                """;
        RoutingPlanSnapshot s = RoutingPlanSpecParser.parse(mapper.readTree(json));
        assertTrue(s.legacy());
        assertEquals(RoutingMode.FIXED, s.mode());
        assertEquals(java.util.List.of("bnd_a", "bnd_b", "bnd_c"),
                s.groupCandidates("main"));
        assertTrue(s.preferPreviousInGroup(), "ADR-024 修订：preferPreviousInGroup 不得默认关闭");
        assertNull(s.decision());
    }

    @Test
    void flatSpecFromPlanRowParsesToLegacy() {
        ModelEntities.RoutingPlan plan = new ModelEntities.RoutingPlan(
                "rp1", "r", "HARD", "bnd_a", "bnd_b", "bnd_hard", true, null, "bnd_b", 2);
        RoutingPlanSnapshot s = RoutingPlanSpecParser.parse(
                RoutingPlanSpecParser.flatSpec(mapper, plan));
        assertTrue(s.legacy());
        assertEquals(RoutingMode.FIXED, s.mode());
        assertEquals(java.util.List.of("bnd_hard", "bnd_b"), s.groupCandidates("main"));
    }

    @Test
    void malformedModeFallsBackToLegacy() throws Exception {
        String json = "{\"mode\": 123, \"groups\": {\"main\": [\"bnd_a\"]}}";
        RoutingPlanSnapshot s = RoutingPlanSpecParser.parse(mapper.readTree(json));
        assertTrue(s.legacy(), "mode 非法值回退 legacy FIXED，不阻断调用");
    }

    @Test
    void emptySpecGivesEmptyLegacyFixed() {
        RoutingPlanSnapshot s = RoutingPlanSpecParser.parse(null);
        assertTrue(s.legacy());
        assertEquals(RoutingMode.FIXED, s.mode());
        assertTrue(s.groupCandidates("main").isEmpty());
    }

    @Test
    void decisionTimeoutDefaultsTo1500ms() throws Exception {
        String json = """
                {"mode": "DIFFICULTY", "groups": {"main": ["bnd_a"]},
                 "decision": {"deciderId": "heuristic"}}
                """;
        RoutingPlanSnapshot s = RoutingPlanSpecParser.parse(mapper.readTree(json));
        assertEquals(RoutingPlanSpecParser.DEFAULT_DECIDE_TIMEOUT_MS, s.decision().timeoutMs());
    }
}
