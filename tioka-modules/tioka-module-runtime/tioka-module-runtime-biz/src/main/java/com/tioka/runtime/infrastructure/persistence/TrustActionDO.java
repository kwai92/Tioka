package com.tioka.runtime.infrastructure.persistence;

import java.time.LocalDateTime;

/**
 * trust_action 行（V144 / TRU-05）。无逻辑删；审计列仅 create_at / update_at。
 */
public class TrustActionDO {
    private String id;
    private String workspaceId;
    private String agentId;
    private String agentVersionId;
    private String threadId;
    private String turnId;
    private String orchRunId;
    private String itemId;
    private Integer seq;
    private String toolName;
    private String actionClass;
    private String risk;
    private String reversibility;
    private String status;
    private String gate;
    private String argsJson;
    private String changeSetJson;
    private String fingerprint;
    private Boolean estimated;
    private LocalDateTime validUntil;
    private Integer records;
    private Long amountMicros;
    private String approvalId;
    private String receiptJson;
    private LocalDateTime undoDeadline;
    private String undoneBy;
    private String undoReason;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getAgentVersionId() { return agentVersionId; }
    public void setAgentVersionId(String agentVersionId) { this.agentVersionId = agentVersionId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getOrchRunId() { return orchRunId; }
    public void setOrchRunId(String orchRunId) { this.orchRunId = orchRunId; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getActionClass() { return actionClass; }
    public void setActionClass(String actionClass) { this.actionClass = actionClass; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public String getReversibility() { return reversibility; }
    public void setReversibility(String reversibility) { this.reversibility = reversibility; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getGate() { return gate; }
    public void setGate(String gate) { this.gate = gate; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String argsJson) { this.argsJson = argsJson; }
    public String getChangeSetJson() { return changeSetJson; }
    public void setChangeSetJson(String changeSetJson) { this.changeSetJson = changeSetJson; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Boolean getEstimated() { return estimated; }
    public void setEstimated(Boolean estimated) { this.estimated = estimated; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public Integer getRecords() { return records; }
    public void setRecords(Integer records) { this.records = records; }
    public Long getAmountMicros() { return amountMicros; }
    public void setAmountMicros(Long amountMicros) { this.amountMicros = amountMicros; }
    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public String getReceiptJson() { return receiptJson; }
    public void setReceiptJson(String receiptJson) { this.receiptJson = receiptJson; }
    public LocalDateTime getUndoDeadline() { return undoDeadline; }
    public void setUndoDeadline(LocalDateTime undoDeadline) { this.undoDeadline = undoDeadline; }
    public String getUndoneBy() { return undoneBy; }
    public void setUndoneBy(String undoneBy) { this.undoneBy = undoneBy; }
    public String getUndoReason() { return undoReason; }
    public void setUndoReason(String undoReason) { this.undoReason = undoReason; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}
