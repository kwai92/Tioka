package com.tioka.server.gate;

import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.SseCollector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G0：RT-04。A 执行 Turn，B 订阅 /events；断线续传无重复无缺失。
 */
@Tag("gate")
@EnabledIfSystemProperty(named = "gate", matches = "true")
class G0DualInstanceStreamGateIT {

    @Test
    void subscribeOnOtherInstance() throws Exception {
        try (DualInstance di = DualInstance.start()) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);

            try (SseCollector sse = SseCollector.subscribe(
                    di.baseUrlB(), bundle.token(), bundle.workspaceId(), thr.threadId(), null)) {
                RuntimeApi.TurnHandle handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                        "cm-stream-1", "请依次调用 echo 三次");
                RuntimeApi.TurnView done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
                sse.awaitEvent(RuntimeEventType.TURN_COMPLETED.wire(), 15_000);
                assertTrue(sse.frames().stream().anyMatch(f ->
                                f.data() != null && (f.data().contains("tool_call")
                                        || f.data().contains("message")
                                        || "item.completed".equals(f.event()))),
                        () -> "frames=" + sse.frames());
            }
        }
    }

    @Test
    void resumeWithoutDuplicates() throws Exception {
        try (DualInstance di = DualInstance.start()) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);

            String lastId;
            RuntimeApi.TurnHandle handle;
            try (SseCollector sse = SseCollector.subscribe(
                    di.baseUrlB(), bundle.token(), bundle.workspaceId(), thr.threadId(), null)) {
                handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-resume-1",
                        "请依次调用 echo 三次");
                lastId = sse.awaitIdContaining(".", 30_000);
            }

            EventId cursor = EventId.parse(lastId);
            try (SseCollector sse2 = SseCollector.subscribe(
                    di.baseUrlA(), bundle.token(), bundle.workspaceId(), thr.threadId(), lastId)) {
                RuntimeApi.TurnView done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline
                        && !sse2.hasEvent(RuntimeEventType.TURN_COMPLETED.wire())
                        && sse2.ids().isEmpty()) {
                    Thread.sleep(40);
                }
                List<String> second = sse2.ids();
                for (String id : second) {
                    EventId eid = EventId.parse(id);
                    assertTrue(eid.compareTo(cursor) > 0,
                            "续传不应包含 ≤ Last-Event-ID 的事件 id=" + id + " last=" + lastId
                                    + " second=" + second);
                }
                assertFalse(second.contains(lastId), "不应再推送 Last-Event-ID 自身");
            }
        }
    }
}
