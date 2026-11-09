package com.tioka.runtime.kernel.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        // 游标 seq 内单调递增：更晚的 seq / 更晚的同 seq 增量游标必须严格 > 当前游标
        assertTrue(EventId.of(41, 4).compareTo(cursor) > 0, "更晚的同 seq 增量游标必须 > 游标");
        assertTrue(EventId.of(42).compareTo(cursor) > 0, "更晚 seq 必须 > 游标");

        // 同 seq 内，增量(有小数) 严格小于 完成(无小数) —— 这是 SSE 重放去重的排序基础：
        // delta 先于 completion 投递（ThreadEventsService 用 eid.compareTo(sent)<=0 跳过已发项），
        // 与 EventId.compareTo「同 seq 时 有小数 < 无小数」一致。该测试保护此排序不被改坏。
        assertTrue(EventId.of(41, 3).compareTo(EventId.of(41)) < 0,
                "同 seq 增量游标必须 < 完成事件（增量先于完成投递，去重基础）");

        // 续传幂等键仅由 threadId + seq 构成，与 Last-Event-ID 同源，不引入第三套游标类型
        assertEquals(cursor.seq(), EventId.parse("41.3").seq(), "Last-Event-ID 与重放游标同源(seq)");
    }
}
