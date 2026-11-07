package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

@TableName("orch_subscription")
public class OrchSubscriptionDO extends TechnicalRecordDO {
    private String workItemId;
    private String source;
    private String externalThreadId;
    private LocalDateTime untilAt;
    private LocalDateTime createAt;

    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalThreadId() { return externalThreadId; }
    public void setExternalThreadId(String externalThreadId) { this.externalThreadId = externalThreadId; }
    public LocalDateTime getUntilAt() { return untilAt; }
    public void setUntilAt(LocalDateTime untilAt) { this.untilAt = untilAt; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
}
