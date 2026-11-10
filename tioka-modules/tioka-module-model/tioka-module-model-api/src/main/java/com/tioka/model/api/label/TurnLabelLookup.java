package com.tioka.model.api.label;

import com.tioka.framework.label.DataLabel;

import java.util.Optional;

/**
 * Turn 当前数据标签查询（DAT-03 / M3-02 按标签选模）。
 * model-biz 不读 runtime 表；实现由 runtime-biz 装配
 * （读 {@code runtime_thread.data_label}，V164），缺省视为 PUBLIC。
 */
public interface TurnLabelLookup {

    /** @return Turn/Thread 当前标签；未知 thread 返回 empty（调用方按 PUBLIC 处理）。 */
    Optional<DataLabel> labelOf(String threadId);
}
