package com.tioka.system.api.audit;

import java.nio.file.Path;

/**
 * 审计写入失败时的本地降级落盘（ADR-072：只追加 JSONL，供补录）。
 */
public interface AuditDegradedSink {

    /** 追加一条降级事件；实现须只追加、不截断。 */
    void append(AuditEvent event);

    /** 当前降级文件绝对路径（可观测 / IT）。 */
    Path path();
}
