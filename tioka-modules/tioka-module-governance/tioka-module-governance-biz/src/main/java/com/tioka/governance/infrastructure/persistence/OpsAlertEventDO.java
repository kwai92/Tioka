package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * {@code ops_alert_event}（V155 / ADR-041 §5.3）：告警事件历史（FIRING / RESOLVED）。
 *
 * <p>系统级监控记录，无 {@code workspace_id}（登记 ScopeLine 旁路白名单）；只插入不更新。
 */
@TableName("ops_alert_event")
public class OpsAlertEventDO extends TechnicalRecordDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String ruleId;
    private String ruleName;
    private String targetType;
    private String targetId;
    private String conditionType;
    /** FIRING / RESOLVED。 */
    private String state;
    /** INFO / WARNING / CRITICAL。 */
    private String severity;
    private Double metricValue;
    private Double thresholdValue;
    private String message;
    /** 去重键：rule_id:target_id:condition:state。 */
    private String dedupKey;
    private Boolean notified;
    private LocalDateTime observedAt;
    private LocalDateTime createAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getConditionType() { return conditionType; }
    public void setConditionType(String conditionType) { this.conditionType = conditionType; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Double getMetricValue() { return metricValue; }
    public void setMetricValue(Double metricValue) { this.metricValue = metricValue; }
    public Double getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Double thresholdValue) { this.thresholdValue = thresholdValue; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public Boolean getNotified() { return notified; }
    public void setNotified(Boolean notified) { this.notified = notified; }
    public LocalDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(LocalDateTime observedAt) { this.observedAt = observedAt; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
}
