package com.tioka.runtime.infrastructure.persistence;

import com.tioka.framework.persistence.MutableRecordDO;

import java.time.LocalDateTime;

/**
 * runtime_approval 行（V101）。无逻辑删；审计四列见 {@link MutableRecordDO}（M0-P2）。
 */
public class RuntimeApprovalDO extends MutableRecordDO {
    private String id;
    private String workspaceId;
    private String subjectType;
    private String subjectId;
    private String itemId;
    private String kind;
    private String toolName;
    private String argsJson;
    private String cardJson;
    private String policy;
    private String assigneeJson;
    private String status;
    private String decidedBy;
    private LocalDateTime decidedAt;
    private String answerJson;
    private LocalDateTime expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String argsJson) { this.argsJson = argsJson; }
    public String getCardJson() { return cardJson; }
    public void setCardJson(String cardJson) { this.cardJson = cardJson; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public String getAssigneeJson() { return assigneeJson; }
    public void setAssigneeJson(String assigneeJson) { this.assigneeJson = assigneeJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getAnswerJson() { return answerJson; }
    public void setAnswerJson(String answerJson) { this.answerJson = answerJson; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
