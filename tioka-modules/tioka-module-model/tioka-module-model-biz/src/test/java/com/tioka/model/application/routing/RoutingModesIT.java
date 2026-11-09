package com.tioka.model.application.routing;

import com.tioka.framework.error.BizException;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.routing.Difficulty;
import com.tioka.model.api.routing.RoutingDecider;
import com.tioka.model.application.gateway.ModelBindingResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MH-06（ADR-024「一次选模」）：四种路由模式各至少一例 + preferPreviousInGroup 沿用 + 路由事件断言。
 * 离线（内存仓储 + 假决策器/假 spec 源）。
 */
class RoutingModesIT {

    private final RoutingTestHarness h = new RoutingTestHarness();

    private static String spec(RoutingTestHarness harness, String mode, String groupsJson,
                               String extraJson, String deciderId) {
        String decision = deciderId == null ? ""
                : "\"decision\": {\"deciderId\": \"%s\", \"timeoutMs\": 1500, \"fallbackGroup\": \"main\"},"
                .formatted(deciderId);
        return """
                {"mode": "%s", "groups": %s, %s %s "preferPreviousInGroup": true}
                """.formatted(mode, groupsJson, decision, extraJson == null ? "" : extraJson + ",");
    }

    @Test
    void fixedModeSelectsFirstGroupCandidateAndFreezes() {
        String b1 = h.newBinding("b1");
        String b2 = h.newBinding("b2");
        String planId = "rp_fixed";
        com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO row =
                new com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO();
        row.setId(planId);
        row.setName("fixed");
        row.setType("PRIMARY_FALLBACK");
        row.setPrimaryBindingId(b1);
        row.setPublished(true);
        h.repo.putPlan(row);

        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, spec(h, "FIXED", "{\"main\": [\"" + b1 + "\", \"" + b2 + "\"]}", null, null));
        RoutingPlanExecutor executor = h.executor(source, null, null);

        ModelBindingResolver.Resolution r = executor.select(h.ctx("t1", "u1", planId), h.chatRequest("hello"));
        assertNotNull(r);
        assertEquals(b1, r.bindingId());
        assertEquals("FIXED:main", r.reason());
        // 冻结生效：同 Turn 再次 select 交回既有路径（null）
        assertNull(executor.select(h.ctx("t1", "u1", planId), h.chatRequest("hello")));

        List<com.tioka.model.infrastructure.persistence.ModelRouteEventDO> events =
                h.telemetryRepo.eventsByTurn("u1");
        assertEquals(1, events.size());
        assertEquals("SELECT", events.get(0).getKind());
        assertEquals("FIXED", events.get(0).getMode());
        assertEquals("main", events.get(0).getDecidedGroup());
        assertEquals(b1, events.get(0).getSelectedBindingId());
    }

    @Test
    void legacyFlatPlanFallsBackToLegacyPath() {
        String b1 = h.newBinding("b1");
        String planId = "rp_legacy";
        com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO row =
                new com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO();
        row.setId(planId);
        row.setName("legacy");
        row.setType("PRIMARY_FALLBACK");
        row.setPrimaryBindingId(b1);
        row.setPublished(true);
        h.repo.putPlan(row);
        // 空 spec 源（版本框架未装配且走投影行兜底在实现类内；此处的 null = legacy FIXED）
        RoutingPlanExecutor executor = h.executor(planId2 -> java.util.Optional.empty(), null, null);
        assertNull(executor.select(h.ctx("t2", "u2", planId), h.chatRequest("hello")),
                "V1 平铺 spec 回退既有解析路径");
    }

    @Test
    void difficultyModeMapsDifficultyToGroup() {
        String easy = h.newBinding("easy");
        String hard = h.newBinding("hard");
        String planId = "rp_diff";
        seedPlan(planId);
        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, """
                        {"mode": "DIFFICULTY",
                         "groups": {"light": ["%s"], "heavy": ["%s"]},
                         "difficultyToGroup": {"EASY": "light", "MEDIUM": "light", "HARD": "heavy"},
                         "decision": {"deciderId": "fake", "timeoutMs": 1500, "fallbackGroup": "heavy"},
                         "preferPreviousInGroup": true}
                        """.formatted(easy, hard));
        RoutingPlanExecutor executor = h.executor(source, "fake",
                new RoutingTestHarness.FakeDecider("fake",
                        new RoutingDecider.Decision("general", Difficulty.HARD, 0.9, "长文本")));

        ModelBindingResolver.Resolution r = executor.select(h.ctx("t3", "u3", planId), h.chatRequest("hello"));
        assertEquals(hard, r.bindingId(), "HARD 映射到 heavy 组");

        var events = h.telemetryRepo.eventsByTurn("u3");
        assertEquals(2, events.size());
        assertEquals("DECIDE", events.get(0).getKind());
        assertEquals("fake", events.get(0).getDeciderId());
        assertEquals(0.9, events.get(0).getConfidence());
        assertEquals("SELECT", events.get(1).getKind());
        assertEquals("heavy", events.get(1).getDecidedGroup());
    }

    @Test
    void branchModeMapsCategoryToGroup() {
        String general = h.newBinding("general");
        String coder = h.newBinding("coder");
        String planId = "rp_branch";
        seedPlan(planId);
        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, """
                        {"mode": "BRANCH",
                         "groups": {"chat": ["%s"], "code": ["%s"]},
                         "categoryToGroup": {"general": "chat", "coding": "code"},
                         "decision": {"deciderId": "fake", "timeoutMs": 1500, "fallbackGroup": "chat"},
                         "preferPreviousInGroup": true}
                        """.formatted(general, coder));
        RoutingPlanExecutor executor = h.executor(source, "fake",
                new RoutingTestHarness.FakeDecider("fake",
                        new RoutingDecider.Decision("coding", Difficulty.MEDIUM, 0.8, "代码请求")));

        ModelBindingResolver.Resolution r = executor.select(h.ctx("t4", "u4", planId),
                h.chatRequest("写一段代码"));
        assertEquals(coder, r.bindingId(), "coding 类别映射到 code 组");
    }

    @Test
    void freeFirstEscalatesToMainGroupWhenFreeExhausted() {
        String freeDisabled = h.newDisabledBinding("free");
        String main = h.newBinding("main");
        String planId = "rp_free";
        seedPlan(planId);
        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, """
                        {"mode": "FREE_FIRST",
                         "groups": {"free": ["%s"], "main": ["%s"]},
                         "decision": {"deciderId": "fake", "timeoutMs": 1500, "fallbackGroup": "main"},
                         "escalation": {"freeGroup": "free", "mainGroup": "main"},
                         "preferPreviousInGroup": true}
                        """.formatted(freeDisabled, main));
        RoutingPlanExecutor executor = h.executor(source, "fake",
                new RoutingTestHarness.FakeDecider("fake",
                        new RoutingDecider.Decision("general", Difficulty.EASY, 0.7, "x")));

        ModelBindingResolver.Resolution r = executor.select(h.ctx("t5", "u5", planId),
                h.chatRequest("hello"));
        assertEquals(main, r.bindingId(), "免费组不可用 → 升主力组");

        var kinds = h.telemetryRepo.eventsByTurn("u5").stream()
                .map(e -> e.getKind()).toList();
        assertEquals(List.of("DECIDE", "FAILOVER", "SELECT"), kinds,
                "FREE_FIRST 调决策器（DECIDE）→ 免费组耗尽 FAILOVER → 主力组 SELECT");
        assertEquals("FREE_FIRST:main", r.reason());
        assertTrue(!r.fallbackChain().contains(freeDisabled), "接力链不含已耗尽的免费组候选");
    }

    @Test
    void freeFirstFailsWhenEscalationTargetMissing() {
        String freeDisabled = h.newDisabledBinding("free");
        String planId = "rp_free_dead";
        seedPlan(planId);
        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, """
                        {"mode": "FREE_FIRST",
                         "groups": {"free": ["%s"]},
                         "decision": {"deciderId": "fake", "timeoutMs": 1500},
                         "escalation": {"freeGroup": "free", "mainGroup": null},
                         "preferPreviousInGroup": true}
                        """.formatted(freeDisabled));
        RoutingPlanExecutor executor = h.executor(source, "fake",
                new RoutingTestHarness.FakeDecider("fake",
                        new RoutingDecider.Decision("general", Difficulty.EASY, 0.7, "x")));

        ModelCallContext ctx = h.ctx("t6", "u6", planId);
        BizException ex = assertThrows(BizException.class,
                () -> executor.select(ctx, h.chatRequest("hello")));
        var events = h.telemetryRepo.eventsByTurn("u6");
        assertEquals("FAIL", events.get(events.size() - 1).getKind(), "直接失败写 FAIL 事件");
        assertTrue(ex.getMessage().contains("无可用候选"));
    }

    @Test
    void preferPreviousInGroupReusesPreviousTurnModel() {
        String b1 = h.newBinding("b1");
        String b2 = h.newBinding("b2");
        String planId = "rp_reuse";
        seedPlan(planId);
        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, """
                        {"mode": "DIFFICULTY",
                         "groups": {"g": ["%s", "%s"]},
                         "difficultyToGroup": {"EASY": "g", "MEDIUM": "g", "HARD": "g"},
                         "decision": {"deciderId": "fake", "timeoutMs": 1500},
                         "preferPreviousInGroup": true}
                        """.formatted(b1, b2));
        // Turn1 决策 EASY → 组 g 首候选 b1
        RoutingPlanExecutor executor = h.executor(source, "fake",
                new RoutingTestHarness.FakeDecider("fake",
                        new RoutingDecider.Decision("general", Difficulty.EASY, 0.7, "x")));
        ModelBindingResolver.Resolution r1 = executor.select(h.ctx("t7", "u7", planId), h.chatRequest("a"));
        assertEquals(b1, r1.bindingId());

        // Turn2 决策 MEDIUM → 同组 g，preferPreviousInGroup 沿用 b1（REUSE 事件）
        RoutingPlanExecutor executor2 = h.executor(source, "fake",
                new RoutingTestHarness.FakeDecider("fake",
                        new RoutingDecider.Decision("general", Difficulty.MEDIUM, 0.7, "x")));
        ModelBindingResolver.Resolution r2 = executor2.select(h.ctx("t7", "u8", planId), h.chatRequest("b"));
        assertEquals(b1, r2.bindingId(), "同组沿用上一 Turn 模型");
        assertEquals("reuse:g", r2.reason());

        var kinds = h.telemetryRepo.eventsByTurn("u8").stream().map(e -> e.getKind()).toList();
        assertEquals(List.of("DECIDE", "REUSE"), kinds);
    }

    @Test
    void disabledCandidatesAreSkippedInOrder() {
        String disabled = h.newDisabledBinding("off");
        String b2 = h.newBinding("b2");
        String planId = "rp_skip";
        seedPlan(planId);
        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, spec(h, "FIXED",
                        "{\"main\": [\"" + disabled + "\", \"" + b2 + "\"]}", null, null));
        RoutingPlanExecutor executor = h.executor(source, null, null);
        ModelBindingResolver.Resolution r = executor.select(h.ctx("t9", "u9", planId), h.chatRequest("x"));
        assertEquals(b2, r.bindingId(), "未启用候选被跳过，按序取下一个");
    }

    @Test
    void unknownDeciderFallsBackToFallbackGroup() {
        String b1 = h.newBinding("b1");
        String planId = "rp_unknown_decider";
        seedPlan(planId);
        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource()
                .put(planId, """
                        {"mode": "DIFFICULTY",
                         "groups": {"main": ["%s"]},
                         "difficultyToGroup": {"HARD": "main"},
                         "decision": {"deciderId": "no-such-decider", "timeoutMs": 200, "fallbackGroup": "main"},
                         "preferPreviousInGroup": true}
                        """.formatted(b1));
        RoutingPlanExecutor executor = h.executor(source, null, null);
        ModelBindingResolver.Resolution r = executor.select(h.ctx("t10", "u10", planId), h.chatRequest("x"));
        assertEquals(b1, r.bindingId(), "未知决策器 → 兜底组");
        assertEquals("FAIL", h.telemetryRepo.eventsByTurn("u10").get(0).getKind());
    }

    private void seedPlan(String planId) {
        com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO row =
                new com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO();
        row.setId(planId);
        row.setName(planId);
        row.setType("PRIMARY_FALLBACK");
        row.setPrimaryBindingId("bnd_placeholder");
        row.setPublished(true);
        h.repo.putPlan(row);
    }
}
