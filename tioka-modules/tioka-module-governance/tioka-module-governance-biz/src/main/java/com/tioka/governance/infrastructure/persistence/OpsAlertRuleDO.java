package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * {@code ops_alert_rule}（V155 / ADR-041 §5.3）：告警规则。
 *
 * <p>系统级监控对象，无 {@code workspace_id}（登记 ScopeLine 旁路白名单）；
 * 即时型配置对象，变更写 {@code cfg_change} 流水。
 */
@TableName("ops_alert_rule")
public class OpsAlertRuleDO extends TechnicalRecordDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    /** VENDOR / BINDING / ALIAS / QUOTA_SCOPE / ALL。 */
    private String targetType;
    private String targetId;
    /** 见 {@code AlertConditionType}。 */
    private String conditionType;
    /** GT / GTE / LT / LTE / EQ / NE。 */
    private String comparator;
    private Double thresholdValue;
    private String thresholdText;
    private Integer windowMinutes;
    /** INFO / WARNING / CRITICAL。 */
    private String severity;
    /** 逗号分隔渠道：in_app / im:feishu / email。 */
    private String channels;
    /** 逗号分隔接收人；空表示系统缺省。 */
    private String recipients;
    private String silenceStart;
    private String silenceEnd;
    private Boolean enabled;
    private Boolean builtin;
    /** 事件型条件（配置发布 / 回滚）的游标水位。 */
    private LocalDateTime lastFiredAt;
    private String description;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;
    private String createBy;
    private String updateBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getConditionType() { return conditionType; }
    public void setConditionType(String conditionType) { this.conditionType = conditionType; }
    public String getComparator() { return comparator; }
    public void setComparator(String comparator) { this.comparator = comparator; }
    public Double getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Double thresholdValue) { this.thresholdValue = thresholdValue; }
    public String getThresholdText() { return thresholdText; }
    public void setThresholdText(String thresholdText) { this.thresholdText = thresholdText; }
    public Integer getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(Integer windowMinutes) { this.windowMinutes = windowMinutes; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }
    public String getRecipients() { return recipients; }
    public void setRecipients(String recipients) { this.recipients = recipients; }
    public String getSilenceStart() { return silenceStart; }
    public void setSilenceStart(String silenceStart) { this.silenceStart = silenceStart; }
    public String getSilenceEnd() { return silenceEnd; }
    public void setSilenceEnd(String silenceEnd) { this.silenceEnd = silenceEnd; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getBuiltin() { return builtin; }
    public void setBuiltin(Boolean builtin) { this.builtin = builtin; }
    public LocalDateTime getLastFiredAt() { return lastFiredAt; }
    public void setLastFiredAt(LocalDateTime lastFiredAt) { this.lastFiredAt = lastFiredAt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
