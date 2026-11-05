package com.tioka.system.api.audit;

import java.time.Instant;
import java.util.Map;

/**
 * 审计事件，对应只增不改的 {@code audit_event} 表（ADR-017）。
 *
 * @param actorType user / service / system
 * @param action    小写点分动作，如 {@code agent.publish}、{@code tool.call}
 */
public record AuditEvent(String id, Instant ts, String tenantId, String workspaceId,
                         String actorType, String actorId, String action,
                         String resourceType, String resourceId,
                         AuditOutcome outcome, String reason, String traceId, String clientIp,
                         Map<String, Object> details) {
    public AuditEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
