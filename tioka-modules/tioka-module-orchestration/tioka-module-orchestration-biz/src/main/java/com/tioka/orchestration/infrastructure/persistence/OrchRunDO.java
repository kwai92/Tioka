package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * V132 {@code orch_run}：有 {@code workspace_id}/状态机更新，无逻辑删、无 create_by/update_by，
 * 故用 {@link TechnicalRecordDO}（非 {@link com.tioka.framework.persistence.WorkspaceAwareDO}/{@code BaseDO}）。
 */
@TableName("orch_run")
public class OrchRunDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String workItemId;
    private String eventId;
    private String ruleVersionId;
    private String status;
    private Integer attempt;
    private Integer rewriteCount;
    private String inputJson;
    private String threadId;
    private String currentTurnId;
    private String receiptJson;
    private String runAsJson;
    private String errorCode;
    private String errorMessage;
    /** OBS-03 / V133：关联上下文。 */
    private String requestId;
    private String subject;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;
    private LocalDateTime endedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(String ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Integer getRewriteCount() { return rewriteCount; }
    public void setRewriteCount(Integer rewriteCount) { this.rewriteCount = rewriteCount; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getCurrentTurnId() { return currentTurnId; }
    public void setCurrentTurnId(String currentTurnId) { this.currentTurnId = currentTurnId; }
    public String getReceiptJson() { return receiptJson; }
    public void setReceiptJson(String receiptJson) { this.receiptJson = receiptJson; }
    public String getRunAsJson() { return runAsJson; }
    public void setRunAsJson(String runAsJson) { this.runAsJson = runAsJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}
