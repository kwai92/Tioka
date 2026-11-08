package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

@TableName("orch_decision")
public class OrchDecisionDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String eventId;
    private String ruleVersionId;
    private String strength;
    private String decision;
    private String reason;
    private String workItemId;
    private LocalDateTime createAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(String ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public String getStrength() { return strength; }
    public void setStrength(String strength) { this.strength = strength; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
}
