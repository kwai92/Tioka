package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * {@code sys_api_key}（FND-06）。表列为 TIMESTAMPTZ，时间字段用 {@link Instant}（不可用 BaseDO 的 LocalDateTime）。
 */
@TableName("sys_api_key")
public class SysApiKeyDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String name;
    private String keyPrefix;
    private String keyHash;
    private String serviceUserId;
    private String scopes;
    private Instant expiresAt;
    private Instant lastUsedAt;

    /** TIMESTAMPTZ：由应用层写入 Instant（MetaObjectHandler 只填 LocalDateTime）。 */
    private Instant createAt;
    private Instant updateAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Boolean deleted;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getServiceUserId() { return serviceUserId; }
    public void setServiceUserId(String serviceUserId) { this.serviceUserId = serviceUserId; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getCreateAt() { return createAt; }
    public void setCreateAt(Instant createAt) { this.createAt = createAt; }
    public Instant getUpdateAt() { return updateAt; }
    public void setUpdateAt(Instant updateAt) { this.updateAt = updateAt; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
}
