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
        int limit,
        int offset
) {}
