package com.tioka.runtime.application.pipeline;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.application.tool.RunBlastRadiusStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TRU-08 第 11 步离线验收（M3-05 §6）：阈值判定与「未配限额不改变行为」。 */
class BlastRadiusPipelineStepTest {

    @Test
    void withinLimitContinues() {
        // Usage(amountMicros, records, unknown)：累计 3 条 / 900 micros，均在阈值内
        assertFalse(BlastRadiusPipelineStep.exceeded(
                new RunBlastRadiusStore.Usage(900L, 3L, false), 5, 1000L, 400L));
    }

    @Test
    void accumulatedRecordsOverPerRunLimitEscalates() {
        assertTrue(BlastRadiusPipelineStep.exceeded(
                new RunBlastRadiusStore.Usage(900L, 6L, false), 5, null, 400L));
    }

    @Test
    void singleCallAmountOverPerCallLimitEscalates() {
        assertTrue(BlastRadiusPipelineStep.exceeded(
                new RunBlastRadiusStore.Usage(9_000L, 1L, false), null, 1000L, 9_000L));
    }

    @Test
    void redisUnavailableIsConservative() {
        assertTrue(BlastRadiusPipelineStep.exceeded(
                RunBlastRadiusStore.Usage.unknownUsage(), 5, 1000L, 0L));
    }

    @Test
    void missingCharterLeavesPipelineUnchanged() {
        AgentSnapshotApi snapshots = mock(AgentSnapshotApi.class);
        when(snapshots.get("av_1")).thenReturn(new AgentSnapshotApi.AgentSnapshot(
                "av_1", "agt_1", 1, "ws_1", "owner_1", "hash", null, Instant.now(), "ACTIVE"));
        RunBlastRadiusStore store = mock(RunBlastRadiusStore.class);
        BlastRadiusPipelineStep step = new BlastRadiusPipelineStep(
                provider(snapshots), provider(store), true);
        assertEquals(11.0, step.order(), "第 11 步序号不得漂移");
        assertEquals("blast-radius", step.name());
        ToolGateway.ToolCallContext call = new ToolGateway.ToolCallContext(
                new Principal("u_1", "ws_1", PrincipalType.USER, Set.of(), Set.of(), "password"),
                "thr_1", "trn_1", "av_1", "chat", List.of(), Map.of(), null);
        ToolPipelineContext ctx = new ToolPipelineContext(call, "erp.update", Map.of());
        assertEquals(ToolPipelineVerdict.CONTINUE, step.execute(ctx));
    }

    @Test
    void featureSwitchOffSkips() {
        BlastRadiusPipelineStep step = new BlastRadiusPipelineStep(
                provider(mock(AgentSnapshotApi.class)), provider(mock(RunBlastRadiusStore.class)), false);
        ToolPipelineContext ctx = new ToolPipelineContext(null, "erp.update", Map.of());
        assertEquals(ToolPipelineVerdict.CONTINUE, step.execute(ctx));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
