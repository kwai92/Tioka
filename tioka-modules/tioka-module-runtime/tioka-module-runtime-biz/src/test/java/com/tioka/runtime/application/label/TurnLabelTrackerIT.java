package com.tioka.runtime.application.label;

import com.tioka.framework.label.DataLabel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Turn 级标签追踪离线测试（DAT-02 / ADR-040 规则 1「只升不降」）：
 * MONOTONIC 提升、非法值忽略、缺省 PUBLIC、写库次数。
 * 存储用内存实现（离线优先惯例）。
 */
class TurnLabelTrackerIT {

    private static class InMemoryStore implements ThreadLabelStore {
        final Map<String, String> rows = new HashMap<>();
        int updates = 0;

        @Override public String current(String threadId) { return rows.get(threadId); }

        @Override public void update(String threadId, DataLabel label) {
            rows.put(threadId, label.name());
            updates++;
        }
    }

    @Test
    void monotonicRaise() {
        InMemoryStore store = new InMemoryStore();
        TurnLabelTracker tracker = new TurnLabelTracker(store);

        // 未设置 = PUBLIC
        assertEquals(DataLabel.PUBLIC, tracker.current("t1"));

        // PUBLIC → CONFIDENTIAL 提升
        assertEquals(DataLabel.CONFIDENTIAL, tracker.record("t1", "CONFIDENTIAL"));
        assertEquals(DataLabel.CONFIDENTIAL, tracker.current("t1"));

        // 降级请求不生效（只升不降）
        assertEquals(DataLabel.CONFIDENTIAL, tracker.record("t1", "INTERNAL"));
        assertEquals(DataLabel.CONFIDENTIAL, tracker.current("t1"));

        // 再升 SECRET
        assertEquals(DataLabel.SECRET, tracker.record("t1", "SECRET"));
        assertEquals(DataLabel.SECRET, tracker.current("t1"));
        assertEquals(2, store.updates); // 两次实际写库
    }

    @Test
    void invalidAndNullIgnored() {
        InMemoryStore store = new InMemoryStore();
        TurnLabelTracker tracker = new TurnLabelTracker(store);

        tracker.record("t2", "CONFIDENTIAL");
        assertEquals(DataLabel.CONFIDENTIAL, tracker.record("t2", "not-a-label"));
        assertEquals(DataLabel.CONFIDENTIAL, tracker.record("t2", null));
        assertEquals(DataLabel.CONFIDENTIAL, tracker.record("t2", "  "));
        // threadId 缺失 = 无处可记，返回 PUBLIC
        assertEquals(DataLabel.PUBLIC, tracker.record(null, "SECRET"));
        assertEquals(DataLabel.PUBLIC, tracker.record(" ", "SECRET"));
        assertEquals(1, store.updates); // 只有第一次提升写库
    }

    @Test
    void caseInsensitiveParseAndLegacyCorruptValue() {
        InMemoryStore store = new InMemoryStore();
        TurnLabelTracker tracker = new TurnLabelTracker(store);

        // 小写 + 前后空白可解析
        assertEquals(DataLabel.SECRET, tracker.record("t3", "  secret "));
        assertEquals(DataLabel.SECRET, tracker.current("t3"));

        // 存量脏值（非法）→ 视为 PUBLIC，新提升照常生效
        store.rows.put("t4", "GARBAGE");
        assertNull(TurnLabelTracker.parse("GARBAGE"));
        assertEquals(DataLabel.PUBLIC, tracker.current("t4"));
        assertEquals(DataLabel.CONFIDENTIAL, tracker.record("t4", "confidential"));
        assertEquals(DataLabel.CONFIDENTIAL, tracker.current("t4"));
    }

    @Test
    void threadsAreIsolated() {
        InMemoryStore store = new InMemoryStore();
        TurnLabelTracker tracker = new TurnLabelTracker(store);

        tracker.record("a", "CONFIDENTIAL");
        tracker.record("b", "INTERNAL");
        assertEquals(DataLabel.CONFIDENTIAL, tracker.current("a"));
        assertEquals(DataLabel.INTERNAL, tracker.current("b"));
        assertEquals(DataLabel.PUBLIC, tracker.current("c"));
    }
}
