package com.tioka.orchestration.receipt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * {@code orch_timer}（V132）；回执 {@code later} 动作 upsert。
 */
@TableName("orch_timer")
public class OrchTimerDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String kind;
    private String ruleId;
    private String workItemId;
    private String timerKey;
    private String cron;
    private String tz;
    private LocalDateTime nextFireAt;
    private String misfire;
    private String targetJson;
    private String status;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getTimerKey() { return timerKey; }
    public void setTimerKey(String timerKey) { this.timerKey = timerKey; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public String getTz() { return tz; }
    public void setTz(String tz) { this.tz = tz; }
    public LocalDateTime getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(LocalDateTime nextFireAt) { this.nextFireAt = nextFireAt; }
    public String getMisfire() { return misfire; }
    public void setMisfire(String misfire) { this.misfire = misfire; }
    public String getTargetJson() { return targetJson; }
    public void setTargetJson(String targetJson) { this.targetJson = targetJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}
