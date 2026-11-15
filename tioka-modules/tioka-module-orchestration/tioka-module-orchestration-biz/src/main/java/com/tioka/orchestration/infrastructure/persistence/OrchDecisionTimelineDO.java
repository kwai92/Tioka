package com.tioka.orchestration.infrastructure.persistence;

import java.time.LocalDateTime;

/**
 * {@code orch_decision} 左连 {@code orch_event} 的时间线只读行（OBS-08 / ADR-075 §2、§6）。
 *
 * <p>决定表本身不带主体与空间（{@code orch_decision} 无 {@code workspace_id}/{@code subject}），
 * 关联键经 {@code event_id} 从 {@code orch_event} 取 {@code subject_key}/{@code workspace_id}，
 * 供重建来源投影 {@code DECISION} 条目（请求 / 主体两视角可查）。
 */
public class OrchDecisionTimelineDO {
    private String id;
    private String eventId;
    private String ruleVersionId;
    private String strength;
    private String decision;
    private String reason;
    private String workItemId;
    private LocalDateTime createAt;
    private String subjectKey;
    private String workspaceId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(String ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public String getStrength() { return strength; }
    public void setStrength(String strength) { this.strength = strength; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public String getSubjectKey() { return subjectKey; }
    public void setSubjectKey(String subjectKey) { this.subjectKey = subjectKey; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
}
