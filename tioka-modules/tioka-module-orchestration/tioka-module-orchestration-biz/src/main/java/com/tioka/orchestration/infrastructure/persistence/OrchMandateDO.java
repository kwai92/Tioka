package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

@TableName("orch_mandate")
public class OrchMandateDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workItemId;
    private String ruleVersionId;
    private String serviceAccountId;
    private String strength;
    private String via;
    private String requesterSource;
    private String requesterExternalId;
    private String requesterUserId;
    private String scopeJson;
    /** 派工目标 Agent 版本（V201 / ADR-029 第 36 条：工单级授权记录关联数字员工版本）。 */
    private String agentVersionId;
    private String status;
    private String revokedBy;
    private String revokedReason;
    private LocalDateTime createAt;
    private LocalDateTime revokedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(String ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public String getServiceAccountId() { return serviceAccountId; }
    public void setServiceAccountId(String serviceAccountId) { this.serviceAccountId = serviceAccountId; }
    public String getStrength() { return strength; }
    public void setStrength(String strength) { this.strength = strength; }
    public String getVia() { return via; }
    public void setVia(String via) { this.via = via; }
    public String getRequesterSource() { return requesterSource; }
    public void setRequesterSource(String requesterSource) { this.requesterSource = requesterSource; }
    public String getRequesterExternalId() { return requesterExternalId; }
    public void setRequesterExternalId(String requesterExternalId) { this.requesterExternalId = requesterExternalId; }
    public String getRequesterUserId() { return requesterUserId; }
    public void setRequesterUserId(String requesterUserId) { this.requesterUserId = requesterUserId; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public String getAgentVersionId() { return agentVersionId; }
    public void setAgentVersionId(String agentVersionId) { this.agentVersionId = agentVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRevokedBy() { return revokedBy; }
    public void setRevokedBy(String revokedBy) { this.revokedBy = revokedBy; }
    public String getRevokedReason() { return revokedReason; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
}
