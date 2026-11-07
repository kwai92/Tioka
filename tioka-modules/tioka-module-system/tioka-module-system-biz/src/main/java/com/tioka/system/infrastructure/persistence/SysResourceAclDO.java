package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.MutableRecordDO;

@TableName("sys_resource_acl")
public class SysResourceAclDO extends MutableRecordDO {
    private String resourceType;
    private String resourceId;
    private String subjectType;
    private String subjectId;
    private String level;

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
}
