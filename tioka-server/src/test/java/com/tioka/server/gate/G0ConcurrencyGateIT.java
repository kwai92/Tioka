package com.tioka.server.gate;

import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G0：RT-01。100 个 thread 各发一个 Turn（模型桩延迟 500ms），总耗时 &lt; 3s。
 */
@Tag("gate")
@EnabledIfSystemProperty(named = "gate", matches = "true")
class G0ConcurrencyGateIT {

    @Test
    void hundredThreadsDoNotBlock() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(
                "tioka.runtime.executor.max-concurrent-turns", 128,
                "tioka.runtime.executor.queue-capacity", 256,
                "spring.datasource.hikari.maximum-pool-size", 40), false)) {
            KernelProperties props = di.a().getBean(KernelProperties.class);
            assertTrue(props.getExecutor().getMaxConcurrentTurns() >= 100,
                    "maxConcurrentTurns=" + props.getExecutor().getMaxConcurrentTurns());

            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            List<String> turnIds = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);
                turnIds.add(GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                        "cm-c-" + i, "ping").turnId());
            }
            long t0 = System.currentTimeMillis();
            List<CompletableFuture<RuntimeApi.TurnView>> futures = turnIds.stream()
                    .map(id -> CompletableFuture.supplyAsync(() ->
                            GateSeed.awaitTerminal(di.a(), bundle, id, 45_000)))
                    .toList();
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                TurnRepository turns = di.a().getBean(TurnRepository.class);
                String summary = turnIds.stream()
                        .map(id -> id + "=" + turns.findById(id).map(t -> t.getStatus()).orElse("?"))
                        .collect(Collectors.joining(", "));
                fail("并发等待失败: " + e.getMessage() + "; statuses=" + summary);
            }
            for (CompletableFuture<RuntimeApi.TurnView> f : futures) {
                RuntimeApi.TurnView v = f.get();
                assertEquals(TurnStatus.COMPLETED, v.status(),
                        () -> "turn=" + v.turnId() + " err=" + v.errorMessage());
            }
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed < 3_000, "期望并行完成 <3s，实际 " + elapsed + "ms");
        }
    }
}
