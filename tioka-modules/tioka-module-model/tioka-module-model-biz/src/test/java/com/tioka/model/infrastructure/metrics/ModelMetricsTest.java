package com.tioka.model.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MH-10（ADR-041 第 5.2 节）：指标登记与取值单测（SimpleMeterRegistry，离线）。
 */
class ModelMetricsTest {

    @Test
    void countersRecordedWithExpectedTags() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ModelMetrics m = new ModelMetrics(reg);

        m.recordCall("p1", "b1", "a1", "chat", "SUCCESS");
        m.recordError("p1", "b1", "429");
        m.recordTokens("p1", "b1", "in", 100);
        m.recordCostMicros("p1", "b1", 500);
        m.recordFailover("b1", "http-500");
        m.recordRouteDecision("plan1", "g1", "decider1", "decide");
        m.recordLatency("p1", "b1", "chat", 250);

        assertEquals(1.0d, reg.get(ModelMetrics.CALLS).tag("status", "SUCCESS").counter().count(), 1e-9);
        assertEquals(1.0d, reg.get(ModelMetrics.ERRORS).tag("error_class", "429").counter().count(), 1e-9);
        assertEquals(100.0d, reg.get(ModelMetrics.TOKENS).tag("direction", "in").counter().count(), 1e-9);
        assertEquals(500.0d, reg.get(ModelMetrics.COST_MICROS).counter().count(), 1e-9);
        assertEquals(1.0d, reg.get(ModelMetrics.FAILOVER).tag("reason", "http-500").counter().count(), 1e-9);
        assertEquals(1.0d, reg.get(ModelMetrics.ROUTE_DECISIONS).tag("outcome", "decide").counter().count(), 1e-9);
        assertEquals(1L, reg.get(ModelMetrics.LATENCY).tag("binding", "b1").timer().count());
    }

    @Test
    void tokensSkipNonPositive() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ModelMetrics m = new ModelMetrics(reg);
        m.recordTokens("p1", "b1", "out", 0);
        m.recordCostMicros("p1", "b1", 0);
        assertNull(reg.find(ModelMetrics.TOKENS).counter());
        assertNull(reg.find(ModelMetrics.COST_MICROS).counter());
    }

    @Test
    void gaugesHoldLastValue() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ModelMetrics m = new ModelMetrics(reg);

        m.setCircuitState("b1", 1d);
        m.setCircuitState("b1", 2d);
        m.setQuotaUsageRatio("provider", "p1", "tokens_month", 0.75d);

        assertEquals(2.0d, reg.get(ModelMetrics.CIRCUIT_STATE).tag("binding", "b1").gauge().value(), 1e-9);
        assertEquals(0.75d, reg.get(ModelMetrics.QUOTA_USAGE_RATIO)
                .tag("scope_id", "p1").tag("metric", "tokens_month").gauge().value(), 1e-9);
    }

    @Test
    void missingRegistryIsNoOp() {
        ModelMetrics m = new ModelMetrics((MeterRegistry) null);
        assertDoesNotThrow(() -> {
            m.recordCall("p1", "b1", "a1", "chat", "SUCCESS");
            m.recordError("p1", "b1", "429");
            m.recordLatency("p1", "b1", "chat", 10);
            m.recordTokens("p1", "b1", "in", 5);
            m.setCircuitState("b1", 1d);
            m.setQuotaUsageRatio("provider", "p1", "tokens_month", 1d);
        });
    }
}
