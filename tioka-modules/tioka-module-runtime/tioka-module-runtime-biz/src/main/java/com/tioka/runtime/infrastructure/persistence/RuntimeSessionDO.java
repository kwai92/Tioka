package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("runtime_session")
public class RuntimeSessionDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String status;
    private String agentVersionId;
    private String routingPlanId;
    private String workspaceId;
    private Boolean cancelled;
    private String steerText;
    private String partsJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAgentVersionId() { return agentVersionId; }
    public void setAgentVersionId(String agentVersionId) { this.agentVersionId = agentVersionId; }
    public String getRoutingPlanId() { return routingPlanId; }
    public void setRoutingPlanId(String routingPlanId) { this.routingPlanId = routingPlanId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Boolean getCancelled() { return cancelled; }
    public void setCancelled(Boolean cancelled) { this.cancelled = cancelled; }
    public String getSteerText() { return steerText; }
    public void setSteerText(String steerText) { this.steerText = steerText; }
    public String getPartsJson() { return partsJson; }
    public void setPartsJson(String partsJson) { this.partsJson = partsJson; }
}
