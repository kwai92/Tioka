package com.tioka.server.gate;

import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.application.LeaseReaper;
import com.tioka.runtime.kernel.application.RuntimeTestHooks;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.SseCollector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G1：RT-06。Turn 执行工具时关掉持有实例，另一实例接管续跑至 COMPLETED。
 */
@Tag("gate")
class G1RecoveryGateIT {

    @Test
    void turnTakenOverAfterCrash() throws Exception {
        try (DualInstance di = DualInstance.start("three-tools", Map.of(
                "tioka.runtime.lease-ttl", "5s",
                "tioka.runtime.lease-renew-interval", "2s",
                "tioka.runtime.lease-reaper-interval-ms", "500",
                "tioka.runtime.queued-sweeper-interval-ms", "60000"))) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            var thr = GateSeed.createThread(di.a(), bundle);

            // 双实例都冻结：避免非 crash 目标抢到 Turn 后无冻结跑完全程
            di.a().getBean(RuntimeTestHooks.class).freeze();
            di.b().getBean(RuntimeTestHooks.class).freeze();

            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                    "cm-recovery-crash", "please use tools");

            ItemRepository itemsA = di.a().getBean(ItemRepository.class);
            TurnRepository turnsA = di.a().getBean(TurnRepository.class);
            long deadline = System.currentTimeMillis() + 30_000;
            boolean sawToolCall = false;
            String workerId = null;
            while (System.currentTimeMillis() < deadline) {
                List<RuntimeItemDO> calls = itemsA.listByThread(thr.threadId()).stream()
                        .filter(i -> ItemType.TOOL_CALL.wire().equals(i.getType()))
                        .toList();
                if (!calls.isEmpty()) {
                    RuntimeTurnDO row = turnsA.findById(handle.turnId()).orElseThrow();
                    if (TurnStatus.RUNNING.name().equals(row.getStatus())
                            && row.getWorkerId() != null && !row.getWorkerId().isBlank()) {
                        sawToolCall = true;
                        workerId = row.getWorkerId();
                        break;
                    }
                }
                Thread.sleep(40);
            }
            assertTrue(sawToolCall, "应先写出 tool_call 再阻塞于工具执行");
            assertNotNull(workerId);

            int crashIdx = "A".equals(workerId) ? 0 : 1;
            int survivorIdx = crashIdx == 0 ? 1 : 0;
            String survivorId = crashIdx == 0 ? "B" : "A";

            try (SseCollector sse = SseCollector.subscribe(
                    di.cluster().baseUrl(survivorIdx), bundle.token(), bundle.workspaceId(),
                    thr.threadId(), null)) {
                plantInFlightStream(di.cluster().instance(survivorIdx), thr.threadId(), handle.turnId());
                di.cluster().crash(crashIdx);
                di.cluster().instance(survivorIdx).getBean(RuntimeTestHooks.class).unfreeze();

                TurnRepository turnsSurvivor = di.cluster().instance(survivorIdx).getBean(TurnRepository.class);
                LeaseReaper reaperSurvivor = di.cluster().instance(survivorIdx).getBean(LeaseReaper.class);
                JdbcTemplate jdbc = di.cluster().instance(survivorIdx).getBean(JdbcTemplate.class);
                long reapDeadline = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < reapDeadline) {
                    RuntimeTurnDO row = turnsSurvivor.findById(handle.turnId()).orElseThrow();
                    if (row.getRecoverCount() != null && row.getRecoverCount() >= 1) {
                        break;
                    }
                    if (TurnStatus.RUNNING.name().equals(row.getStatus())) {
                        jdbc.update("UPDATE runtime_turn SET lease_until = now() - interval '1 second' "
                                + "WHERE id = ? AND status = 'RUNNING'", handle.turnId());
                        reaperSurvivor.reap();
                    }
                    Thread.sleep(100);
                }

                var done = GateSeed.awaitTerminal(
                        di.cluster().instance(survivorIdx), bundle, handle.turnId(), 90_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

                RuntimeTurnDO finalRow = turnsSurvivor.findById(handle.turnId()).orElseThrow();
                assertEquals(1, finalRow.getRecoverCount());
                assertEquals(survivorId, finalRow.getWorkerId());

                List<RuntimeItemDO> lost = di.cluster().instance(survivorIdx).getBean(ItemRepository.class)
                        .listByThread(thr.threadId()).stream()
                        .filter(i -> ItemType.TOOL_RESULT.wire().equals(i.getType()))
                        .filter(i -> i.getContentJson() != null
                                && i.getContentJson().contains(LeaseReaper.LOST_TOOL_OUTPUT))
                        .toList();
                assertFalse(lost.isEmpty(), "应补写「结果丢失」tool_result");

                sse.awaitEvent(RuntimeEventType.ITEM_DISCARDED.wire(), 20_000);
            }
        }
    }

    private static void plantInFlightStream(org.springframework.context.ConfigurableApplicationContext ctx,
                                            String threadId, String turnId) {
        EventStream streams = ctx.getBean(EventStream.class);
        RuntimeEventFactory events = ctx.getBean(RuntimeEventFactory.class);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        Long seq = jdbc.queryForObject(
                "UPDATE runtime_thread SET last_item_seq = last_item_seq + 1, update_at = now() "
                        + "WHERE id = ? RETURNING last_item_seq",
                Long.class, threadId);
        assertNotNull(seq);
        String itemId = Ids.newId("itm");
        String entry = streams.publish(threadId, EventId.of(seq, 0).toString(),
                RuntimeEventType.ITEM_CREATED.wire(),
                events.itemCreated(threadId, turnId, itemId, seq, ItemType.MESSAGE.wire()));
        streams.setCurrentEntry(threadId, entry);
    }
}
