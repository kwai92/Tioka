package com.tioka.system.api.spi;

import java.util.Map;

/** 审计外送。 */
public interface AuditSink {
    void emit(String action, String actorId, Map<String, Object> detail);
}
