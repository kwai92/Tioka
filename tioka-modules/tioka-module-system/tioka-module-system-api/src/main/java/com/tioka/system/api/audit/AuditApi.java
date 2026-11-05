package com.tioka.system.api.audit;

import java.time.Instant;
import java.util.List;

/** 审计写入与查询（ADR-017）。外送 SIEM 由 {@code AuditSink} 异步完成。 */
public interface AuditApi {

    /** 在当前事务内写入（SUCCESS）。 */
    void record(AuditEvent event);

    /** 在独立事务内写入（DENIED / ERROR）。 */
    void recordIndependent(AuditEvent event);

    List<AuditEvent> query(AuditQuery query);

    record AuditQuery(String workspaceId, String actorId, String action, String resourceType,
                      String resourceId, AuditOutcome outcome, Instant from, Instant to,
                      String cursor, int limit) {}
}
