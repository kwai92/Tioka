package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.ImmutableSnapshotDO;

/**
 * 规则版本内容不可变；仅 stage / canary_percent 与 F17 发布型字段可在发布时更新。
 */
@TableName("orch_rule_version")
public class OrchRuleVersionDO extends ImmutableSnapshotDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String ruleId;
    private Integer version;
    private String eventType;
    private String conditionCel;
    private String actionsJson;
    private String concurrency;
    private String acceptMode;
    private String tell;
    private String emitsJson;
    private String scheduleJson;
    private String escalateJson;
    private String serviceAccountId;
    private String stage;
    private Integer canaryPercent;
    /** F17 / ADR-041：规范化内容哈希（SHA-256 hex） */
    private String contentHash;
    /** F17 / ADR-041：发布说明 */
    private String publishNote;
    /** F17 / ADR-041：上一个生效版本 id */
    private String previousVersionId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getConditionCel() { return conditionCel; }
    public void setConditionCel(String conditionCel) { this.conditionCel = conditionCel; }
    public String getActionsJson() { return actionsJson; }
    public void setActionsJson(String actionsJson) { this.actionsJson = actionsJson; }
    public String getConcurrency() { return concurrency; }
    public void setConcurrency(String concurrency) { this.concurrency = concurrency; }
    public String getAcceptMode() { return acceptMode; }
    public void setAcceptMode(String acceptMode) { this.acceptMode = acceptMode; }
    public String getTell() { return tell; }
    public void setTell(String tell) { this.tell = tell; }
    public String getEmitsJson() { return emitsJson; }
    public void setEmitsJson(String emitsJson) { this.emitsJson = emitsJson; }
    public String getScheduleJson() { return scheduleJson; }
    public void setScheduleJson(String scheduleJson) { this.scheduleJson = scheduleJson; }
    public String getEscalateJson() { return escalateJson; }
    public void setEscalateJson(String escalateJson) { this.escalateJson = escalateJson; }
    public String getServiceAccountId() { return serviceAccountId; }
    public void setServiceAccountId(String serviceAccountId) { this.serviceAccountId = serviceAccountId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Integer getCanaryPercent() { return canaryPercent; }
    public void setCanaryPercent(Integer canaryPercent) { this.canaryPercent = canaryPercent; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getPublishNote() { return publishNote; }
    public void setPublishNote(String publishNote) { this.publishNote = publishNote; }
    public String getPreviousVersionId() { return previousVersionId; }
    public void setPreviousVersionId(String previousVersionId) { this.previousVersionId = previousVersionId; }
}
