package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("auth_user")
public class AuthUserDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private Boolean enabled;
    private String idp;
    private String externalSubject;
    private String defaultWorkspaceId;
    private Boolean mustChangePassword;
    private String email;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getIdp() { return idp; }
    public void setIdp(String idp) { this.idp = idp; }
    public String getExternalSubject() { return externalSubject; }
    public void setExternalSubject(String externalSubject) { this.externalSubject = externalSubject; }
    public String getDefaultWorkspaceId() { return defaultWorkspaceId; }
    public void setDefaultWorkspaceId(String defaultWorkspaceId) { this.defaultWorkspaceId = defaultWorkspaceId; }
    public Boolean getMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(Boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
