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
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
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
 * 闸门 G1：RT-06。Turn 执行工具时关掉 A，B 接管续跑至 COMPLETED。
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

            // 先冻结：工具进入 await 后卡住，保证 crash 时仍在工具执行中
            di.a().getBean(RuntimeTestHooks.class).freeze();

            try (SseCollector sse = SseCollector.subscribe(
                    di.baseUrlB(), bundle.token(), bundle.workspaceId(), thr.threadId(), null)) {
                var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                        "cm-recovery-crash", "please use tools");

                ItemRepository itemsA = di.a().getBean(ItemRepository.class);
                long deadline = System.currentTimeMillis() + 30_000;
                boolean sawToolCall = false;
                while (System.currentTimeMillis() < deadline) {
                    List<RuntimeItemDO> calls = itemsA.listBySession(thr.threadId()).stream()
                            .filter(i -> ItemType.TOOL_CALL.wire().equals(i.getType()))
                            .toList();
                    if (!calls.isEmpty()) {
                        sawToolCall = true;
                        break;
                    }
                    Thread.sleep(40);
                }
                assertTrue(sawToolCall, "应先写出 tool_call 再阻塞于工具执行");

                plantInFlightStream(di.b(), thr.threadId(), handle.turnId());
                di.cluster().crash(0);

                TurnRepository turnsB = di.b().getBean(TurnRepository.class);
                LeaseReaper reaperB = di.b().getBean(LeaseReaper.class);
                JdbcTemplate jdbc = di.b().getBean(JdbcTemplate.class);
                long reapDeadline = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < reapDeadline) {
                    RuntimeTurnDO row = turnsB.findById(handle.turnId()).orElseThrow();
                    if (row.getRecoverCount() != null && row.getRecoverCount() >= 1) {
                        break;
                    }
                    if (TurnStatus.RUNNING.name().equals(row.getStatus())) {
                        jdbc.update("UPDATE runtime_turn SET lease_until = now() - interval '1 second' "
                                + "WHERE id = ? AND status = 'RUNNING'", handle.turnId());
                        reaperB.reap();
                    }
                    Thread.sleep(100);
                }

                var done = GateSeed.awaitTerminal(di.b(), bundle, handle.turnId(), 90_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

                RuntimeTurnDO finalRow = turnsB.findById(handle.turnId()).orElseThrow();
                assertEquals(1, finalRow.getRecoverCount());
                assertEquals("B", finalRow.getWorkerId());

                List<RuntimeItemDO> lost = di.b().getBean(ItemRepository.class)
                        .listBySession(thr.threadId()).stream()
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
                "UPDATE runtime_session SET last_item_seq = last_item_seq + 1, update_at = now() "
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
