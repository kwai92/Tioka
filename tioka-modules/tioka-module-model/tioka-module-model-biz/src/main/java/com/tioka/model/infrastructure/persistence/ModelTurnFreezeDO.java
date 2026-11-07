package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

import java.time.LocalDateTime;

/**
 * 轮次模型冻结（V102 {@code model_turn_freeze}；ADR-017）。
 */
@TableName("model_turn_freeze")
public class ModelTurnFreezeDO extends AppendOnlyDO {
    private String threadId;
    private String turnId;
    private String bindingId;
    private String reason;
    private Integer failoverCount;
    private LocalDateTime updateAt;
    private String updateBy;

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getFailoverCount() { return failoverCount; }
    public void setFailoverCount(Integer failoverCount) { this.failoverCount = failoverCount; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
