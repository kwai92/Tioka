package com.tioka.framework.persistence;

/**
 * 空间级资源实体基类。
 */
public abstract class WorkspaceAwareDO extends BaseDO {

    private String workspaceId;

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }
}
