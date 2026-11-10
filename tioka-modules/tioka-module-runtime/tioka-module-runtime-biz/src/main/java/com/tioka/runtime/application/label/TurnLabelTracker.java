package com.tioka.runtime.application.label;

import com.tioka.framework.label.DataLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Turn 级数据标签追踪（DAT-02 / ADR-040 规则 1「只升不降」）。
 *
 * <p>写入点：
 * <ul>
 *   <li>{@code MemoryItemSink.toolResult}——工具结果 item 携带 {@code data_label} 时随写随升；</li>
 *   <li>{@code SubAgentReaper.onChildTerminal}——子 thread 终态标签回流父 thread（agent.delegate）。</li>
 * </ul>
 * 语义：thread.data_label = max(旧值, 本 Turn 各工具结果标签)；null / 非法值不参与。
 */
@Component
public class TurnLabelTracker {

    private static final Logger log = LoggerFactory.getLogger(TurnLabelTracker.class);

    private final ThreadLabelStore store;

    public TurnLabelTracker(ThreadLabelStore store) {
        this.store = store;
    }

    /**
     * MONOTONIC 记录：仅在高于当前值时写库。返回提升后的 thread 标签。
     *
     * @param labelRaw 工具结果携带的标签（null / 非法值忽略）
     */
    public DataLabel record(String threadId, String labelRaw) {
        DataLabel incoming = parse(labelRaw);
        if (threadId == null || threadId.isBlank() || incoming == null) {
            return current(threadId);
        }
        DataLabel now = current(threadId);
        DataLabel next = DataLabel.max(now, incoming);
        if (!Objects.equals(next, now)) {
            store.update(threadId, next);
            log.info("thread label raised: thread={} {} -> {}", threadId, now, next);
        }
        return next;
    }

    /** 当前 thread 标签（未设置 = PUBLIC）。 */
    public DataLabel current(String threadId) {
        String raw = threadId == null || threadId.isBlank() ? null : store.current(threadId);
        DataLabel parsed = parse(raw);
        return parsed == null ? DataLabel.PUBLIC : parsed;
    }

    static DataLabel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DataLabel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
