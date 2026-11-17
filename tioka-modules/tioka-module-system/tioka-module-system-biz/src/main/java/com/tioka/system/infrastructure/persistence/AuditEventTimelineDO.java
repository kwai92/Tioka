package com.tioka.system.infrastructure.persistence;

import java.time.Instant;

/**
 * {@code audit_event}（ADR-072）时间线重建读回行：只取时间线需要的列（OBS-08 / ADR-075 §2）。
 *
 * <p>{@code subject} 为 ADR-073 §2 的主体引用（{@code type:id}）；{@code request_id}/{@code turn_id}/
 * {@code run_id} 为关联键。本类无 {@code @TableName}，不参与 ScopeLine 表扫描。
 */
public class AuditEventTimelineDO {
    private String id;
    private Instant ts;
    private String workspaceId;
    private String actorType;
    private String actorId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String outcome;
    private String reason;
    private String traceId;
    private String requestId;
    private String turnId;
    private String runId;
    private String subject;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
}
