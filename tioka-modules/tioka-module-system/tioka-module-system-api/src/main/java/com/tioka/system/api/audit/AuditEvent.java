package com.tioka.system.api.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 审计事件，对应只增不改的 {@code audit_event} 表（ADR-017；F6：builder 构造）。
 *
 * <p>无 {@code tenantId}（ADR-029 第 60 条 2026-11-07 修订；47-GL）。
 *
 * @param actorType user / service / system
 * @param action    小写点分动作，如 {@code agent.publish}、{@code tool.invoke}
 * @param spanId    W3C parent-span-id（HTTP traceparent / MQ 消费侧回写；无则 null，P2-L6）
 * @param service   产生事件的服务名（tioka.observability.service-name，P2-L6）
 */
public record AuditEvent(String id, Instant ts, String workspaceId,
                         String actorType, String actorId, String action,
                         String resourceType, String resourceId,
                         AuditOutcome outcome, String reason, String traceId, String spanId, String service,
                         String requestId, String clientIp, Map<String, Object> details) {
    public AuditEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private Instant ts;
        private String workspaceId;
        private String actorType;
        private String actorId;
        private String action;
        private String resourceType;
        private String resourceId;
        private AuditOutcome outcome;
        private String reason;
        private String traceId;
        private String spanId;
        private String service;
        private String requestId;
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

        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public Builder service(String service) {
            this.service = service;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
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
            return new AuditEvent(id, ts, workspaceId, actorType, actorId, action,
                    resourceType, resourceId, outcome, reason, traceId, spanId, service, requestId, clientIp, details);
        }
    }
}
