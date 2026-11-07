package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * {@code sys_resource_acl}（V110 / FND-04）。TIMESTAMPTZ 列用 {@link Instant}
 *（不可继承 MutableRecordDO 的 LocalDateTime，见 SysApiKeyDO）。
 */
@TableName("sys_resource_acl")
public class SysResourceAclDO {
    private String resourceType;
    private String resourceId;
    private String subjectType;
    private String subjectId;
    private String level;
    private Instant createAt;
    private Instant updateAt;
    private String createBy;
    private String updateBy;

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Instant getCreateAt() { return createAt; }
    public void setCreateAt(Instant createAt) { this.createAt = createAt; }
    public Instant getUpdateAt() { return updateAt; }
    public void setUpdateAt(Instant updateAt) { this.updateAt = updateAt; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
