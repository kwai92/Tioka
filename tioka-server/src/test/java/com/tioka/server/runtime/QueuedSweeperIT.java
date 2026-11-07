package com.tioka.server.runtime;

import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.application.QueuedSweeper;
import com.tioka.runtime.kernel.application.TurnDispatcher;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-06 W5：Redis 丢失后由 QueuedSweeper 从库重建派发。
 */
@Tag("it")
class QueuedSweeperIT {

    @Test
    void rebuildAfterRedisFlush() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(
                "tioka.runtime.queued-sweeper-interval-ms", "1000",
                "tioka.runtime.lease-ttl", "30s",
                "tioka.runtime.lease-renew-interval", "5s"), false)) {
            TurnDispatcher dispatcher = di.a().getBean(TurnDispatcher.class);
            dispatcher.stopConsuming();
            Thread.sleep(100);

            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);

            List<String> turnIds = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                turnIds.add(GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                        "cm-sweep-" + i, "ping").turnId());
            }

            TurnRepository turns = di.a().getBean(TurnRepository.class);
            Thread.sleep(200);
            for (String id : turnIds) {
                assertEquals(TurnStatus.QUEUED.name(),
                        turns.findById(id).orElseThrow().getStatus(),
                        () -> "Dispatcher 已停，应变 QUEUED: " + id);
            }

            JdbcTemplate jdbc = di.a().getBean(JdbcTemplate.class);
            jdbc.update(
                    "UPDATE runtime_turn SET update_at = now() - interval '20 seconds' WHERE session_id = ?",
                    thr.threadId());

            StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
            redis.getConnectionFactory().getConnection().serverCommands().flushAll();

            // 消费组需重建（FLUSHALL 抹掉 Stream）
            di.a().getBean(com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue.class)
                    .ensureConsumerGroup();

            dispatcher.startConsuming();

            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allDone = turnIds.stream().allMatch(id -> {
                    RuntimeTurnDO t = turns.findById(id).orElse(null);
                    return t != null && TurnStatus.valueOf(t.getStatus()).isTerminal();
                });
                if (allDone) {
                    break;
                }
                // 主动扫一次，避免只依赖调度抖动
                di.a().getBean(QueuedSweeper.class).sweep();
                Thread.sleep(200);
            }

            for (String id : turnIds) {
                RuntimeTurnDO t = turns.findById(id).orElseThrow();
                assertEquals(TurnStatus.COMPLETED.name(), t.getStatus(),
                        () -> id + " " + t.getErrorMessage());
            }
        }
    }
}
