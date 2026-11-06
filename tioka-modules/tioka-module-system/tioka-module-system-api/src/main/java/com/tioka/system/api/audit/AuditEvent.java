package com.tioka.system.api.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 审计事件，对应只增不改的 {@code audit_event} 表（ADR-017；F6：builder 构造）。
 *
 * <p>{@code tenantId} 字段仅为历史契约占位，单租户下恒为 null（ADR-029 第 60 条）。
 *
 * @param actorType user / service / system
 * @param action    小写点分动作，如 {@code agent.publish}、{@code tool.invoke}
 */
public record AuditEvent(String id, Instant ts, String tenantId, String workspaceId,
                         String actorType, String actorId, String action,
                         String resourceType, String resourceId,
                         AuditOutcome outcome, String reason, String traceId, String clientIp,
                         Map<String, Object> details) {
    public AuditEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private Instant ts;
        private String tenantId;
        private String workspaceId;
        private String actorType;
        private String actorId;
        private String action;
        private String resourceType;
        private String resourceId;
        private AuditOutcome outcome;
        private String reason;
        private String traceId;
        private String clientIp;
        private Map<String, Object> details = Map.of();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder ts(Instant ts) {
            this.ts = ts;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder actorType(String actorType) {
            this.actorType = actorType;
            return this;
        }

        public Builder actorId(String actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder outcome(AuditOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder details(Map<String, Object> details) {
            this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
            return this;
        }

        public AuditEvent build() {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(outcome, "outcome");
            return new AuditEvent(id, ts, tenantId, workspaceId, actorType, actorId, action,
                    resourceType, resourceId, outcome, reason, traceId, clientIp, details);
        }
    }
}
