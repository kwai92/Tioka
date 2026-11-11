package com.tioka.runtime.api.kernel;

import java.util.Optional;

/**
 * 会话数据标签只读端口（DAT-02 / V164 runtime_thread.data_label）。
 * EVO-01 轨迹导出做标签限制时使用；实现：runtime-biz TrajectoryQueryService。
 */
public interface ThreadLabelApi {

    /** @return 会话数据标签（PUBLIC/INTERNAL/CONFIDENTIAL/SECRET），thread 不存在时 empty。 */
    Optional<String> dataLabel(String threadId);
}
