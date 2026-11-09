package com.tioka.model.application.routing;

import com.tioka.model.api.routing.Difficulty;
import com.tioka.model.api.routing.RoutingDecider;
import com.tioka.model.application.gateway.ModelBindingResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MH-06（ADR-024）：决策超时/失败 → 兜底组 + 路由时间线记录原因。离线。
 */
class DeciderTimeoutFallbackIT {

    private final RoutingTestHarness h = new RoutingTestHarness();

    /** 建两难度组 + DIFFICULTY 计划（决策器 slow，超时 timeoutMs，兜底组 heavy）。 */
    private RoutingTestHarness.MapSpecSource planWith(String bLight, String bHeavy, int timeoutMs) {
        String planId = "rp_timeout";
        com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO row =
                new com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO();
        row.setId(planId);
        row.setName(planId);
        row.setType("PRIMARY_FALLBACK");
        row.setPrimaryBindingId(bHeavy);
        row.setPublished(true);
        h.repo.putPlan(row);
        return new RoutingTestHarness.MapSpecSource().put(planId, """
                {"mode": "DIFFICULTY",
                 "groups": {"light": ["%s"], "heavy": ["%s"]},
                 "difficultyToGroup": {"EASY": "light", "MEDIUM": "light", "HARD": "heavy"},
                 "decision": {"deciderId": "slow", "timeoutMs": %d, "fallbackGroup": "heavy"},
                 "preferPreviousInGroup": true}
                """.formatted(bLight, bHeavy, timeoutMs));
    }

    @Test
    void deciderTimeoutFallsBackToFallbackGroupAndWritesFailEvent() {
        String light = h.newBinding("light");
        String heavy = h.newBinding("heavy");
        var source = planWith(light, heavy, 120);
        RoutingTestHarness.FakeDecider slow = new RoutingTestHarness.FakeDecider("slow",
                new RoutingDecider.Decision("general", Difficulty.EASY, 0.7, "x"),
                () -> {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        RoutingPlanExecutor executor = h.executor(source, "slow", slow);

        ModelBindingResolver.Resolution r = executor.select(h.ctx("tt1", "tu1", "rp_timeout"),
                h.chatRequest("hello"));
        assertEquals(heavy, r.bindingId(), "决策超时走兜底组 heavy");

        var events = h.telemetryRepo.eventsByTurn("tu1");
        assertEquals(2, events.size());
        assertEquals("FAIL", events.get(0).getKind());
        assertEquals("slow", events.get(0).getDeciderId());
        assertEquals("heavy", events.get(0).getDecidedGroup());
        assertTrue(events.get(0).getReason().contains("超时"), "时间线记录超时原因");
        assertNotNull(events.get(0).getLatencyMs());
        assertEquals("SELECT", events.get(1).getKind());
    }

    @Test
    void deciderExceptionFallsBackAndRecordsReason() {
        String light = h.newBinding("light");
        String heavy = h.newBinding("heavy");
        var source = planWith(light, heavy, 1000);
        RoutingTestHarness.FakeDecider broken = new RoutingTestHarness.FakeDecider("slow",
                new RoutingDecider.Decision("general", Difficulty.EASY, 0.7, "x"),
                () -> { throw new IllegalStateException("决策服务 5xx"); });
        RoutingPlanExecutor executor = h.executor(source, "slow", broken);

        ModelBindingResolver.Resolution r = executor.select(h.ctx("tt2", "tu2", "rp_timeout"),
                h.chatRequest("hello"));
        assertEquals(heavy, r.bindingId());
        assertEquals("FAIL", h.telemetryRepo.eventsByTurn("tu2").get(0).getKind());
        assertTrue(h.telemetryRepo.eventsByTurn("tu2").get(0).getReason().contains("5xx"));
    }

    @Test
    void deciderWithinTimeoutSucceeds() {
        String light = h.newBinding("light");
        String heavy = h.newBinding("heavy");
        var source = planWith(light, heavy, 1500);
        RoutingTestHarness.FakeDecider fast = new RoutingTestHarness.FakeDecider("slow",
                new RoutingDecider.Decision("general", Difficulty.HARD, 0.95, "长文"));
        RoutingPlanExecutor executor = h.executor(source, "slow", fast);

        ModelBindingResolver.Resolution r = executor.select(h.ctx("tt3", "tu3", "rp_timeout"),
                h.chatRequest("hello"));
        assertEquals(heavy, r.bindingId(), "HARD → heavy（正常决策路径，非兜底）");
        assertEquals("DECIDE", h.telemetryRepo.eventsByTurn("tu3").get(0).getKind());
    }
}
