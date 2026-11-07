package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

/**
 * 模型用量明细（V102 {@code model_usage}；只增不改）。
 */
@TableName("model_usage")
public class ModelUsageDO extends AppendOnlyDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String userId;
    private String agentId;
    private String threadId;
    private String turnId;
    private String purpose;
    private String bindingId;
    private String modelRef;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long costMicros;
    private Integer latencyMs;
    private String status;
    /** V143：按 Turn 步对齐轨迹用量；历史行可空。 */
    private Integer step;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getModelRef() { return modelRef; }
    public void setModelRef(String modelRef) { this.modelRef = modelRef; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Long getCostMicros() { return costMicros; }
    public void setCostMicros(Long costMicros) { this.costMicros = costMicros; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getStep() { return step; }
    public void setStep(Integer step) { this.step = step; }
}
