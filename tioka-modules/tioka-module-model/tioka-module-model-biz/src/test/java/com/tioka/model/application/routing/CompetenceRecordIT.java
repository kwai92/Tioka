package com.tioka.model.application.routing;

import com.tioka.model.domain.RoutingTelemetry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MH-07（ADR-024「胜任度」）：Turn 结束打 0–1 分只记录不参与决策（M3 口径）。
 * 离线（内存仓储）。
 */
class CompetenceRecordIT {

    private final RoutingTestHarness h = new RoutingTestHarness();
    private final RoutingTelemetry telemetry = h.telemetry;

    @Test
    void recordsScoreAndReadsBack() {
        assertTrue(telemetry.recordCompetence("ws1", "bnd_a", "turn_1", 0.85, "user_feedback"));
        assertEquals(1, h.telemetryRepo.allCompetences().size());
        assertEquals(0.85, telemetry.latestCompetenceScore("bnd_a").orElseThrow());

        // 读作下一次决策的 previousCompetence 输入（M3 只记录，不参与选模）
        assertTrue(telemetry.latestCompetenceScore("bnd_missing").isEmpty());
    }

    @Test
    void duplicateTurnScoreIsIgnored() {
        assertTrue(telemetry.recordCompetence("ws1", "bnd_a", "turn_1", 0.8, "user_feedback"));
        assertFalse(telemetry.recordCompetence("ws1", "bnd_a", "turn_1", 0.9, "judge_model"),
                "UNIQUE(turn_id, binding_id) 语义：同 Turn 同 binding 幂等");
        assertEquals(1, h.telemetryRepo.allCompetences().size());
        assertEquals(0.8, telemetry.latestCompetenceScore("bnd_a").orElseThrow());
    }

    @Test
    void scoreMustBeWithinZeroToOne() {
        assertThrows(IllegalArgumentException.class,
                () -> telemetry.recordCompetence("ws1", "bnd_a", "turn_1", 1.5, "user_feedback"));
        assertThrows(IllegalArgumentException.class,
                () -> telemetry.recordCompetence("ws1", "bnd_a", "turn_1", -0.1, "user_feedback"));
    }

    @Test
    void scoreMustBelongToTurn() {
        assertThrows(IllegalArgumentException.class,
                () -> telemetry.recordCompetence("ws1", "bnd_a", null, 0.5, "user_feedback"));
    }

    @Test
    void routeEventsRecordedAsTurnTimeline() {
        telemetry.recordEvent("ws1", "th1", "turn_1", "rp1", 3,
                RoutingTelemetry.KIND_DECIDE, "DIFFICULTY", "heuristic", null, null,
                0.6, "启发式: 长度=10", 5);
        telemetry.recordEvent("ws1", "th1", "turn_1", "rp1", 3,
                RoutingTelemetry.KIND_SELECT, "DIFFICULTY", null, "main", "bnd_a",
                null, "组内选中: main", null);
        assertEquals(2, telemetry.eventsByTurn("turn_1").size());
        assertEquals(RoutingTelemetry.KIND_DECIDE, telemetry.eventsByTurn("turn_1").get(0).kind());
    }
}
