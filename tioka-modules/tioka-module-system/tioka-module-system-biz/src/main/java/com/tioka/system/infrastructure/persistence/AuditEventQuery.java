package com.tioka.system.infrastructure.persistence;

import java.time.Instant;

/** 审计查询条件（48-CS：≥3 业务入参收敛为对象）。 */
public record AuditEventQuery(
        String workspaceId,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        Instant from,
        Instant to,
        /* ADR-073 §1 关联键条件（OBS-09） */
        String traceId,
        String requestId,
        String subject,
        String turnId,
        String runId,
        /* (ts, id) 游标：DESC 序下取 ts < cursorTs 或 (ts = cursorTs 且 id < cursorId) */
        Instant cursorTs,
        String cursorId,
        int limit
) {

    /** 兼容旧 10 参口径（关联键 / 游标为 null）。 */
    public AuditEventQuery(String workspaceId, String actorId, String action, String resourceType,
                           String resourceId, String outcome, Instant from, Instant to,
                           int limit, int offset) {
        this(workspaceId, actorId, action, resourceType, resourceId, outcome, from, to,
                null, null, null, null, null, null, null, limit);
    }
}
