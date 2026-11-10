package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code trust_undo_request}（V202 / TRU-07）：撤销请求记录（范围 / 请求人 / 结果清单）。
 * UNIQUE(scope_type, scope_id) 保证同范围撤销串行幂等（第二次返回第一次结果）。
 */
@TableName("trust_undo_request")
public class TrustUndoRequestDO extends TechnicalRecordDO {

    @TableId
    private String id;
    private String workspaceId;
    private String scopeType;
    private String scopeId;
    private String requester;
    private String status;
    private String compensatedJson;
    private String irreversibleJson;
    private String failedActionId;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public String getRequester() { return requester; }
    public void setRequester(String requester) { this.requester = requester; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCompensatedJson() { return compensatedJson; }
    public void setCompensatedJson(String compensatedJson) { this.compensatedJson = compensatedJson; }
    public String getIrreversibleJson() { return irreversibleJson; }
    public void setIrreversibleJson(String irreversibleJson) { this.irreversibleJson = irreversibleJson; }
    public String getFailedActionId() { return failedActionId; }
    public void setFailedActionId(String failedActionId) { this.failedActionId = failedActionId; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}
