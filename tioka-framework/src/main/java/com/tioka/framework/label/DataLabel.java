package com.tioka.framework.label;

/**
 * 数据标签（ADR-040 / M3-02）。序：PUBLIC &lt; INTERNAL &lt; CONFIDENTIAL &lt; SECRET。
 */
public enum DataLabel {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    SECRET;

    public boolean atMost(DataLabel other) {
        return this.ordinal() <= other.ordinal();
    }

    public static DataLabel max(DataLabel a, DataLabel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public static DataLabel min(DataLabel a, DataLabel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
