package com.tioka.framework.persistence;

/** 空间级资源：插入时可由 MetaObjectHandler 填充 workspaceId。 */
public interface WorkspaceScoped {
    String getWorkspaceId();
    void setWorkspaceId(String workspaceId);
}
