package com.tioka.runtime.infrastructure.persistence;

import com.tioka.framework.persistence.MutableRecordDO;

import java.time.LocalDateTime;

/**
 * runtime_human_task 行（V233，SCH-09 / ADR-066 §7.4）：结构化输出人工补录任务。
 * 无逻辑删；审计四列见 {@link MutableRecordDO}（M0-P2）。
 */
public class RuntimeHumanTaskDO extends MutableRecordDO {
    private String id;
    private String workspaceId;
    private String subjectType;
    private String subjectId;
    private String threadId;
    private String turnId;
    private String itemId;
    private String kind;
    private String schemaRef;
    private String candidateJson;
    private String pendingPointers;
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
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getSchemaRef() { return schemaRef; }
    public void setSchemaRef(String schemaRef) { this.schemaRef = schemaRef; }
    public String getCandidateJson() { return candidateJson; }
    public void setCandidateJson(String candidateJson) { this.candidateJson = candidateJson; }
    public String getPendingPointers() { return pendingPointers; }
    public void setPendingPointers(String pendingPointers) { this.pendingPointers = pendingPointers; }
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
