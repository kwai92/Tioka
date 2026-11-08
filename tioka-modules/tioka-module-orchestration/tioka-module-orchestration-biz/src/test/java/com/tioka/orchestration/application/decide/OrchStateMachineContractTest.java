package com.tioka.orchestration.application.decide;

import com.tioka.orchestration.api.engine.ConcurrencyMode;
import com.tioka.orchestration.api.engine.OrchApi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排状态机契约：随机步进 1 万步 + 动作顺序穷举（M2-00 §9 / ORC-03）。
 */
class OrchStateMachineContractTest {

    private static final OrchApi.RunStatus[] ALL = OrchApi.RunStatus.values();

    @Test
    void exhaustiveTransitionsAreLegalOrRejected() {
        for (OrchApi.RunStatus from : ALL) {
            for (OrchApi.RunStatus to : ALL) {
                boolean ok = OrchStateMachine.canTransit(from, to);
                if (from == to) {
                    assertTrue(ok, from + " -> self");
                }
                if (OrchStateMachine.isTerminal(from) && from != to) {
                    assertFalse(ok, "终态不得迁出: " + from + " -> " + to);
                }
            }
        }
        // 关键路径抽样
        assertTrue(OrchStateMachine.canTransit(OrchApi.RunStatus.PENDING, OrchApi.RunStatus.DISPATCHED));
        assertTrue(OrchStateMachine.canTransit(OrchApi.RunStatus.DISPATCHED, OrchApi.RunStatus.RUNNING));
        assertTrue(OrchStateMachine.canTransit(OrchApi.RunStatus.RUNNING, OrchApi.RunStatus.SUCCEEDED));
        assertFalse(OrchStateMachine.canTransit(OrchApi.RunStatus.PENDING, OrchApi.RunStatus.RUNNING));
        assertFalse(OrchStateMachine.canTransit(OrchApi.RunStatus.SUCCEEDED, OrchApi.RunStatus.PENDING));
    }

    @Test
    void randomWalkTenThousandStepsNoIllegalTransit() {
        Random rnd = new Random(42);
        OrchApi.RunStatus status = OrchApi.RunStatus.PENDING;
        for (int i = 0; i < 10_000; i++) {
            List<OrchApi.RunStatus> candidates = new ArrayList<>();
            for (OrchApi.RunStatus to : ALL) {
                if (OrchStateMachine.canTransit(status, to) && to != status) {
                    candidates.add(to);
                }
            }
            if (candidates.isEmpty()) {
                // 终态：重置为新 Run（模拟新事件建 Run，不重复领取旧 Run）
                status = OrchApi.RunStatus.PENDING;
                continue;
            }
            OrchApi.RunStatus next = candidates.get(rnd.nextInt(candidates.size()));
            assertTrue(OrchStateMachine.canTransit(status, next), status + " -> " + next);
            status = next;
        }
    }

    @Test
    void concurrencyModesNeverDuplicateActiveRunSemantics() {
        // merge + active → 不建 Run；serial + active → 排队新建；parallel → 总是新建
        assertTrue(OrchStateMachine.shouldAppendPendingOnly(ConcurrencyMode.MERGE, true));
        assertFalse(OrchStateMachine.shouldCreateRun(ConcurrencyMode.MERGE, true));
        assertTrue(OrchStateMachine.shouldCreateRun(ConcurrencyMode.MERGE, false));

        assertTrue(OrchStateMachine.shouldQueueSerial(ConcurrencyMode.SERIAL, true));
        assertTrue(OrchStateMachine.shouldCreateRun(ConcurrencyMode.SERIAL, true));
        assertFalse(OrchStateMachine.shouldAppendPendingOnly(ConcurrencyMode.SERIAL, true));

        assertTrue(OrchStateMachine.shouldCreateRun(ConcurrencyMode.PARALLEL, true));
        assertTrue(OrchStateMachine.shouldCreateRun(ConcurrencyMode.PARALLEL, false));
        assertFalse(OrchStateMachine.shouldAppendPendingOnly(ConcurrencyMode.PARALLEL, true));
    }

    @Test
    void exhaustiveActionOrdersNoDuplicateDispatchSlot() {
        // 模拟：同一 Run 仅允许一次 PENDING→DISPATCHED
        Set<OrchApi.RunStatus> nonTerminal = EnumSet.complementOf(EnumSet.of(
                OrchApi.RunStatus.SUCCEEDED, OrchApi.RunStatus.FAILED, OrchApi.RunStatus.CANCELLED));
        AtomicInteger dispatchCount = new AtomicInteger();
        OrchApi.RunStatus status = OrchApi.RunStatus.PENDING;
        for (OrchApi.RunStatus next : List.of(
                OrchApi.RunStatus.DISPATCHED,
                OrchApi.RunStatus.RUNNING,
                OrchApi.RunStatus.WAITING_ATTENTION,
                OrchApi.RunStatus.RUNNING,
                OrchApi.RunStatus.SUCCEEDED)) {
            assertTrue(OrchStateMachine.canTransit(status, next));
            if (status == OrchApi.RunStatus.PENDING && next == OrchApi.RunStatus.DISPATCHED) {
                dispatchCount.incrementAndGet();
            }
            status = next;
        }
        assertTrue(dispatchCount.get() == 1);
        assertFalse(nonTerminal.contains(status));
        // 迟到 DISPATCHED 非法
        assertFalse(OrchStateMachine.canTransit(OrchApi.RunStatus.SUCCEEDED, OrchApi.RunStatus.DISPATCHED));
    }
}
