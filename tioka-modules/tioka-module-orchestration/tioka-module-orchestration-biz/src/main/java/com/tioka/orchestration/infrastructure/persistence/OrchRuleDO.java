package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("orch_rule")
public class OrchRuleDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String workspaceId;
    private String ownerId;
    private String stableVersionId;
    private String canaryVersionId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getStableVersionId() { return stableVersionId; }
    public void setStableVersionId(String stableVersionId) { this.stableVersionId = stableVersionId; }
    public String getCanaryVersionId() { return canaryVersionId; }
    public void setCanaryVersionId(String canaryVersionId) { this.canaryVersionId = canaryVersionId; }
}
