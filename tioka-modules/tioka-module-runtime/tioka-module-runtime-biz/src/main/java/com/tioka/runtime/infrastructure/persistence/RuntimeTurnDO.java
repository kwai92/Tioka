package com.tioka.runtime.infrastructure.persistence;

import com.tioka.framework.persistence.MutableRecordDO;

import java.time.LocalDateTime;

/**
 * runtime_turn 行。无逻辑删；审计四列见 {@link MutableRecordDO}（M0-P2 / M0-01 ENG-11）。
 */
public class RuntimeTurnDO extends MutableRecordDO {
    private String id;
    private String threadId;
    private Integer seq;
    private String status;
    private String origin;
    private String originRef;
    private String clientMsgId;
    private String runAsJson;
    private String optionsJson;
    private Integer stepCount;
    /** 崩溃续跑次数（V114 / RT-06）。 */
    private Integer recoverCount;
    private String workerId;
    private Long leaseVersion;
    private LocalDateTime leaseUntil;
    private String errorCode;
    private String errorMessage;
    private Integer promptTokens;
    private Integer completionTokens;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getOriginRef() { return originRef; }
    public void setOriginRef(String originRef) { this.originRef = originRef; }
    public String getClientMsgId() { return clientMsgId; }
    public void setClientMsgId(String clientMsgId) { this.clientMsgId = clientMsgId; }
    public String getRunAsJson() { return runAsJson; }
    public void setRunAsJson(String runAsJson) { this.runAsJson = runAsJson; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
    public Integer getStepCount() { return stepCount; }
    public void setStepCount(Integer stepCount) { this.stepCount = stepCount; }
    public Integer getRecoverCount() { return recoverCount; }
    public void setRecoverCount(Integer recoverCount) { this.recoverCount = recoverCount; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Long getLeaseVersion() { return leaseVersion; }
    public void setLeaseVersion(Long leaseVersion) { this.leaseVersion = leaseVersion; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}
