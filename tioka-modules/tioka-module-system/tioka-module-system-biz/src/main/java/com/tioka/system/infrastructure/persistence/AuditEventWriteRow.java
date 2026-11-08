package com.tioka.system.infrastructure.persistence;

import java.time.Instant;

/** 审计落库行（48-CS：避免 Mapper/Repository 长参列表）。 */
public record AuditEventWriteRow(
        String id,
        Instant ts,
        String workspaceId,
        String actorType,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String reason,
        String traceId,
        String requestId,
        String subject,
        String clientIp,
        String detailsJson
) {}
