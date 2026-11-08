package com.tioka.governance.api.label;

import com.tioka.framework.label.DataLabel;

/**
 * 数据标签去向许可（ADR-040 / DAT-02）。实现在 governance-biz。
 */
public interface SinkGuard {

    SinkCheckResult check(SinkKind kind, String sinkRef, DataLabel label, Object payload);
}
