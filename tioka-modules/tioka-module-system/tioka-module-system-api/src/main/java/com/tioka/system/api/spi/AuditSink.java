package com.tioka.system.api.spi;

import java.util.List;
import java.util.Map;

/** 审计外送。 */
public interface AuditSink {
    void emit(String action, String actorId, Map<String, Object> detail);

    /** 运维 recent 窗口；默认空，FileAuditSink 覆盖（ENG-06）。 */
    default List<Map<String, Object>> recent(int limit) {
        return List.of();
    }
}
