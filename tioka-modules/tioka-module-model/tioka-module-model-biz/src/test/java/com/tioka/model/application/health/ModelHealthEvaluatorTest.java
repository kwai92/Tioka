package com.tioka.model.application.health;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MH-10（ADR-041 第 5.1 节）：六态判定纯函数单测（优先级 + 阈值边界）。
 */
class ModelHealthEvaluatorTest {

    private static ModelHealthSignals s(boolean disabled, boolean budget, boolean cooling,
                                        int probeFailures, long calls5m, long errors5m, long calls429,
                                        double p95, double baseline) {
        return new ModelHealthSignals(disabled, budget, cooling, probeFailures,
                calls5m, errors5m, calls429, p95, baseline);
    }

    @Test
    void disabledWinsOverEverything() {
        assertEquals(ProviderHealthState.DISABLED,
                ModelHealthEvaluator.evaluate(s(true, true, true, 9, 100, 100, 100, 9_999d, 1d)));
    }

    @Test
    void budgetExhaustedBeforeCooldownAndDown() {
        assertEquals(ProviderHealthState.BUDGET_EXHAUSTED,
                ModelHealthEvaluator.evaluate(s(false, true, true, 9, 100, 100, 100, 9_999d, 1d)));
    }

    @Test
    void coolingBeforeDown() {
        assertEquals(ProviderHealthState.COOLDOWN,
                ModelHealthEvaluator.evaluate(s(false, false, true, 9, 100, 100, 100, 9_999d, 1d)));
    }

    @Test
    void downByProbeConsecutiveFailures() {
        assertEquals(ProviderHealthState.DOWN,
                ModelHealthEvaluator.evaluate(s(false, false, false, 3, 0, 0, 0, 0d, 0d)));
        // 2 次失败尚不判 DOWN
        assertEquals(ProviderHealthState.UP,
                ModelHealthEvaluator.evaluate(s(false, false, false, 2, 0, 0, 0, 0d, 0d)));
    }

    @Test
    void downByErrorRateNeedsMinSamplesAndStrictThreshold() {
        // 样本不足（9 次）即使全错也不判 DOWN
        assertEquals(ProviderHealthState.UP,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 9, 9, 0, 0d, 0d)));
        // 恰为 50% 不判（严格大于）
        assertEquals(ProviderHealthState.UP,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 5, 0, 0d, 0d)));
        // 60% > 50% → DOWN
        assertEquals(ProviderHealthState.DOWN,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 6, 0, 0d, 0d)));
    }

    @Test
    void degradedByLatencyRatio() {
        // P95 = 3x 基线 → DEGRADED
        assertEquals(ProviderHealthState.DEGRADED,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 0, 0, 300d, 100d)));
        // 恰 2 倍不判（严格大于）
        assertEquals(ProviderHealthState.UP,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 0, 0, 200d, 100d)));
        // 基线缺失不判
        assertEquals(ProviderHealthState.UP,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 0, 0, 300d, 0d)));
    }

    @Test
    void degradedBy429Ratio() {
        assertEquals(ProviderHealthState.DEGRADED,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 2, 2, 0d, 0d)));
        // 恰 10% 不判
        assertEquals(ProviderHealthState.UP,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 1, 1, 0d, 0d)));
    }

    @Test
    void healthySignalsAreUp() {
        assertEquals(ProviderHealthState.UP,
                ModelHealthEvaluator.evaluate(s(false, false, false, 0, 10, 0, 0, 100d, 100d)));
        assertEquals(ProviderHealthState.UP, ModelHealthEvaluator.evaluate(null));
    }
}
