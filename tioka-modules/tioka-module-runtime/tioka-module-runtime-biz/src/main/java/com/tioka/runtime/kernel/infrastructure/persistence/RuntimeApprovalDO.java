package com.tioka.runtime.kernel.infrastructure.persistence;

import java.time.OffsetDateTime;

/**
 * runtime_approval 行（V101）。无逻辑删，不继承 BaseDO。
 */
public class RuntimeApprovalDO {
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
    private OffsetDateTime decidedAt;
    private String answerJson;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createAt;
    private OffsetDateTime updateAt;
    private String createBy;
    private String updateBy;

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
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getAnswerJson() { return answerJson; }
    public void setAnswerJson(String answerJson) { this.answerJson = answerJson; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreateAt() { return createAt; }
    public void setCreateAt(OffsetDateTime createAt) { this.createAt = createAt; }
    public OffsetDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(OffsetDateTime updateAt) { this.updateAt = updateAt; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
