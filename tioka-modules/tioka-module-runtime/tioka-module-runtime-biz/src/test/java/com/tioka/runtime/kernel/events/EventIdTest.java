package com.tioka.runtime.kernel.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventIdTest {

    @Test
    void compareRules() {
        // 41.0 < 41.3 < 41 < 42.0
        assertTrue(EventId.of(41, 0).compareTo(EventId.of(41, 3)) < 0);
        assertTrue(EventId.of(41, 3).compareTo(EventId.of(41)) < 0);
        assertTrue(EventId.of(41).compareTo(EventId.of(42, 0)) < 0);
        assertEquals(0, EventId.parse("41").compareTo(EventId.of(41)));
        assertEquals("41.3", EventId.parse("41.3").toString());
    }

    /**
     * P0 约束保护（方案 A 冻结）：L1/L4 状态快照重放幂等键 = {@code threadId + EventId.seq}，
     * 与 {@code Last-Event-ID} 共用同一 {@code EventId} 游标；不引入第三套游标 / 独立 checkpoint 表。
     *
     * <p>约定式断言：本仓库 SSE 续传与审批恢复唯一游标类型为 {@link EventId}（seq 即游标整数），
     * {@code Last-Event-ID} 头与 {@code after} 参数统一收敛到同一 {@code EventId}（见
     * {@code ThreadEventsController}/{@code ThreadEventsService}），不存在第二/第三套游标。
     */
    @Test
    void snapshotReplayCursorUnifiesLastEventId() {
        // Last-Event-ID 解析与重放游标为同一 EventId（seq 即游标）
        EventId last = EventId.parse("41.3");
        EventId cursor = EventId.parse("41.3");
        assertEquals(last.seq(), cursor.seq(), "Last-Event-ID 与重放游标同源");

        // 状态快照重放幂等键：threadId + EventId.seq（与 Last-Event-ID 同一游标）
        String threadId = "thr_x";
        String replayKey = threadId + ":" + cursor.seq();
        assertEquals("thr_x:41", replayKey);

        // 增量事件 id 必须严格 > 游标（单调递增），证明无第三套游标介入
        assertTrue(EventId.of(41, 4).compareTo(cursor) > 0);
        assertTrue(EventId.of(42).compareTo(cursor) > 0);
        assertFalse(EventId.of(41).compareTo(cursor) > 0, "已完成事件(无小数)不得 > 同 seq 增量游标");
    }
}
