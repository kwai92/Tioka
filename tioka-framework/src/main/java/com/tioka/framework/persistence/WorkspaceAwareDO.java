package com.tioka.framework.persistence;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

/**
 * 空间级资源实体基类（workspace_id + owner_id；禁止 tenantId）。
 */
public abstract class WorkspaceAwareDO extends BaseDO implements WorkspaceScoped, Owned {

    @TableField(fill = FieldFill.INSERT)
    private String workspaceId;

    @TableField(fill = FieldFill.INSERT)
    private String ownerId;

    @Override
    public String getWorkspaceId() {
        return workspaceId;
    }

    @Override
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    @Override
    public String getOwnerId() {
        return ownerId;
    }

    @Override
    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
}
