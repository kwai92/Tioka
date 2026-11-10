package com.tioka.knowledge.application;

import com.tioka.framework.label.DataLabel;

import java.util.Locale;

/**
 * KB 标签语义（DAT-02 / ADR-040）：结果标签 = 命中文档 data_label 与知识库标签的最大值；
 * {@code maxDataLabel} 以上的文档在检索中过滤掉（KbLabelIT#clearanceFilters 口径）。
 * 纯函数，离线测试 {@code KbLabelIT} 直测。
 */
public final class KbLabels {
    private KbLabels() {}

    /** 解析列值；null / 非法 = fallback（kb 列 fallback INTERNAL，doc 列 fallback 见 effective）。 */
    public static DataLabel parse(String raw, DataLabel fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return DataLabel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** 有效标签 = max(kb 标签（缺省 INTERNAL）, doc 标签（null 继承 kb））。 */
    public static DataLabel effective(String kbLabelRaw, String docLabelRaw) {
        DataLabel kb = parse(kbLabelRaw, DataLabel.INTERNAL);
        DataLabel doc = parse(docLabelRaw, null);
        return doc == null ? kb : DataLabel.max(kb, doc);
    }

    /** 读许可：有效标签 ≤ maxDataLabel。maxDataLabel null = 不过滤（旧口径）。 */
    public static boolean readable(DataLabel effective, DataLabel maxDataLabel) {
        if (maxDataLabel == null) {
            return true;
        }
        return effective.atMost(maxDataLabel);
    }
}
