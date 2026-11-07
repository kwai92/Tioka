package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * 空间成员。表列为 TIMESTAMPTZ，列表查询走 {@code SysMemberMapper#listByUserId} 不映射时间列。
 */
@TableName("sys_member")
public class SysMemberDO extends BaseDO {
    private String workspaceId;
    private String userId;
    private String roleCode;
    /** manual / oidc:{idp}（V113） */
    private String source;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
