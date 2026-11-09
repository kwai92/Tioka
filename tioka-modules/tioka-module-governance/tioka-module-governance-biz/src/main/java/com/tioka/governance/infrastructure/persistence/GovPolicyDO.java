package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** {@code gov_policy}（V200 / ADR-025 数据模型 + source）。策略只能收紧，写入端拒绝 permit。 */
@TableName("gov_policy")
public class GovPolicyDO extends com.tioka.framework.persistence.TechnicalRecordDO {

    @TableId(type = IdType.INPUT)
    private String id;
    /** NULL = 系统级策略。 */
    private String workspaceId;
    private Integer version;
    /** system / import / charter:{agentVersionId}。 */
    private String source;
    /** Cedar @id。 */
    private String policyId;
    private String cedarText;
    /** DRYRUN / ENFORCE。 */
    private String mode;
    /** DENY / ESCALATE。 */
    private String effect;
    /** ACTIVE / DISABLED。 */
    private String status;
    private String createdBy;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }
    public String getCedarText() { return cedarText; }
    public void setCedarText(String cedarText) { this.cedarText = cedarText; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getEffect() { return effect; }
    public void setEffect(String effect) { this.effect = effect; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}
