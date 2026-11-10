package com.tioka.knowledge.application;

import com.tioka.framework.label.DataLabel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KB 标签语义离线测试（DAT-02 / ADR-040）：解析回退、有效标签取 max、读许可过滤。
 * 纯函数直测。
 */
class KbLabelIT {

    @Test
    void parseFallsBackOnNullOrIllegal() {
        assertEquals(DataLabel.CONFIDENTIAL, KbLabels.parse(null, DataLabel.CONFIDENTIAL));
        assertEquals(DataLabel.CONFIDENTIAL, KbLabels.parse("", DataLabel.CONFIDENTIAL));
        assertEquals(DataLabel.CONFIDENTIAL, KbLabels.parse("GARBAGE", DataLabel.CONFIDENTIAL));
        assertEquals(DataLabel.SECRET, KbLabels.parse("  secret ", DataLabel.CONFIDENTIAL));
        assertEquals(DataLabel.PUBLIC, KbLabels.parse("public", DataLabel.INTERNAL));
    }

    @Test
    void effectiveDefaultsKbToInternal() {
        // kb 列 null → 缺省 INTERNAL
        assertEquals(DataLabel.INTERNAL, KbLabels.effective(null, null));
        assertEquals(DataLabel.INTERNAL, KbLabels.effective("", null));
        // kb 列非法 → INTERNAL
        assertEquals(DataLabel.INTERNAL, KbLabels.effective("GARBAGE", null));
        // kb 列显式 PUBLIC → 按显式值（缺省仅指列空）
        assertEquals(DataLabel.PUBLIC, KbLabels.effective("PUBLIC", null));
    }

    @Test
    void effectiveTakesMaxOfKbAndDoc() {
        // doc null → 继承 kb
        assertEquals(DataLabel.CONFIDENTIAL, KbLabels.effective("CONFIDENTIAL", null));
        // doc 高于 kb → doc
        assertEquals(DataLabel.SECRET, KbLabels.effective("INTERNAL", "SECRET"));
        // doc 低于 kb → kb
        assertEquals(DataLabel.CONFIDENTIAL, KbLabels.effective("CONFIDENTIAL", "PUBLIC"));
        // doc 非法 → 继承 kb
        assertEquals(DataLabel.SECRET, KbLabels.effective("SECRET", "GARBAGE"));
        // 两列显式 PUBLIC → PUBLIC
        assertEquals(DataLabel.PUBLIC, KbLabels.effective("PUBLIC", "PUBLIC"));
    }

    @Test
    void readableClearanceFilter() {
        // maxDataLabel null = 不过滤（旧口径）
        assertTrue(KbLabels.readable(DataLabel.SECRET, null));
        // 相等放行
        assertTrue(KbLabels.readable(DataLabel.CONFIDENTIAL, DataLabel.CONFIDENTIAL));
        // 低于许可放行
        assertTrue(KbLabels.readable(DataLabel.INTERNAL, DataLabel.SECRET));
        // 超出许可过滤
        assertFalse(KbLabels.readable(DataLabel.SECRET, DataLabel.CONFIDENTIAL));
        assertFalse(KbLabels.readable(DataLabel.CONFIDENTIAL, DataLabel.INTERNAL));
    }

    @Test
    void parseOfNullReturnsNullFallback() {
        // effective 依赖 doc 的 null fallback 语义
        assertNull(KbLabels.parse(null, null));
        assertNull(KbLabels.parse("GARBAGE", null));
    }
}
