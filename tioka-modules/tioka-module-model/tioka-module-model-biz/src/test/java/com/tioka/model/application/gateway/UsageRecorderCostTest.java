package com.tioka.model.application.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** MH-05：cost_micros 计价公式。 */
class UsageRecorderCostTest {

    @Test
    void roundsHalfUp() {
        // 100 token × 1_000_000 micros/1M = 100；50 × 2_000_000 / 1e6 = 100 → 200
        assertEquals(200L, UsageRecorder.costMicros(100, 50, 1_000_000L, 2_000_000L));
        // 1 × 1 / 1e6 = 0.000001 → 0
        assertEquals(0L, UsageRecorder.costMicros(1, 0, 1L, 0L));
        // 500_000 × 1 / 1e6 = 0.5 → 1
        assertEquals(1L, UsageRecorder.costMicros(500_000, 0, 1L, 0L));
    }
}
