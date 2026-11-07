package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

/** V132 后规则头：空间级资源（workspace_id + owner_id）。 */
@TableName("orch_rule")
public class OrchRuleDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String stableVersionId;
    private String canaryVersionId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStableVersionId() { return stableVersionId; }
    public void setStableVersionId(String stableVersionId) { this.stableVersionId = stableVersionId; }
    public String getCanaryVersionId() { return canaryVersionId; }
    public void setCanaryVersionId(String canaryVersionId) { this.canaryVersionId = canaryVersionId; }
}
