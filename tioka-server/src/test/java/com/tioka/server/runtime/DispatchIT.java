package com.tioka.server.runtime;

import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.application.TurnDispatcher;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-06 W5：跨实例派发。
 */
@Tag("it")
class DispatchIT {

    @Test
    void anyInstanceExecutes() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(
                "tioka.runtime.queued-sweeper-interval-ms", "60000",
                "tioka.runtime.lease-ttl", "30s",
                "tioka.runtime.lease-renew-interval", "5s"))) {
            di.a().getBean(TurnDispatcher.class).stopConsuming();
            Thread.sleep(50);

            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);
            RuntimeApi.TurnHandle handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                    "cm-dispatch-any", "ping");

            RuntimeApi.TurnView done = GateSeed.awaitTerminal(di.b(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

            RuntimeTurnDO row = di.b().getBean(TurnRepository.class).findById(handle.turnId()).orElseThrow();
            assertEquals("B", row.getWorkerId(), "应在 B 上执行（A Dispatcher 已关）");
        }
    }

    @Test
    void threadSerialized() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(
                "tioka.runtime.queued-sweeper-interval-ms", "60000",
                "tioka.runtime.lease-ttl", "30s",
                "tioka.runtime.lease-renew-interval", "5s"), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);

            List<String> turnIds = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                turnIds.add(GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                        "cm-ser-" + i, "ping").turnId());
            }

            TurnRepository turns = di.a().getBean(TurnRepository.class);
            JdbcTemplate jdbc = di.a().getBean(JdbcTemplate.class);
            AtomicInteger maxRunning = new AtomicInteger();
            long deadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < deadline) {
                Integer running = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM runtime_turn WHERE thread_id = ? AND status = 'RUNNING'",
                        Integer.class, thr.threadId());
                if (running != null) {
                    maxRunning.accumulateAndGet(running, Math::max);
                    assertTrue(running <= 1, "任何时刻最多 1 个 RUNNING, got=" + running);
                }
                boolean allDone = turnIds.stream().allMatch(id -> {
                    RuntimeTurnDO t = turns.findById(id).orElse(null);
                    return t != null && TurnStatus.valueOf(t.getStatus()).isTerminal();
                });
                if (allDone) {
                    break;
                }
                Thread.sleep(40);
            }

            List<RuntimeTurnDO> rows = turnIds.stream()
                    .map(id -> turns.findById(id).orElseThrow())
                    .toList();
            for (RuntimeTurnDO t : rows) {
                assertEquals(TurnStatus.COMPLETED.name(), t.getStatus(),
                        () -> t.getId() + " " + t.getErrorMessage());
            }
            assertEquals(List.of(1, 2, 3), rows.stream().map(RuntimeTurnDO::getSeq).toList());
            for (int i = 1; i < rows.size(); i++) {
                assertFalse(rows.get(i).getEndedAt().isBefore(rows.get(i - 1).getEndedAt()),
                        "执行顺序应等于 seq");
            }
            assertTrue(maxRunning.get() >= 1, "应至少观察到一次 RUNNING");
        }
    }
}
