package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

@TableName("sys_user_role")
public class SysUserRoleDO extends AppendOnlyDO {
    private String userId;
    private String roleCode;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
}
