package com.tioka.governance.api.timeline;

/**
 * 时间线条目结局（ADR-075 §2）。
 */
public enum TimelineOutcome {
    OK,
    DENIED,
    ERROR,
    WAITING;

    /** 宽松解析：未知或空值返回 {@code null}（投影不因脏数据失败）。 */
    public static TimelineOutcome parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (TimelineOutcome v : values()) {
            if (v.name().equalsIgnoreCase(raw.trim())) {
                return v;
            }
        }
        return null;
    }
}
