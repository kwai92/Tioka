package com.tioka.runtime.application.label;

import com.tioka.framework.label.DataLabel;

/**
 * Thread 数据标签存取端口（DAT-02）。生产实现走 {@code runtime_thread.data_label}（V164）；
 * 离线测试用内存实现。
 */
public interface ThreadLabelStore {

    /** @return 当前标签；null = 尚未设置（视为 PUBLIC）。 */
    String current(String threadId);

    /** 仅在标签提升时被调用（MONOTONIC 语义由 {@link TurnLabelTracker} 保证）。 */
    void update(String threadId, DataLabel label);
}
