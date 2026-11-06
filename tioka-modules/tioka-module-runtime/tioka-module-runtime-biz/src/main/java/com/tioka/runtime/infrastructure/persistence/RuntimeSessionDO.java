package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

@TableName("runtime_session")
public class RuntimeSessionDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String status;
    private String agentVersionId;
    private String routingPlanId;
    /** V105 已删列；仅热状态内存字段 */
    @TableField(exist = false)
    private Boolean cancelled;
    @TableField(exist = false)
    private String steerText;
    @TableField(exist = false)
    private String partsJson;
    private Long lastItemSeq;
    private String origin;
    private String originRef;
    private String metadataJson;
    private String parentTurnId;
    private Integer depth;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAgentVersionId() { return agentVersionId; }
    public void setAgentVersionId(String agentVersionId) { this.agentVersionId = agentVersionId; }
    public String getRoutingPlanId() { return routingPlanId; }
    public void setRoutingPlanId(String routingPlanId) { this.routingPlanId = routingPlanId; }
    public Boolean getCancelled() { return cancelled; }
    public void setCancelled(Boolean cancelled) { this.cancelled = cancelled; }
    public String getSteerText() { return steerText; }
    public void setSteerText(String steerText) { this.steerText = steerText; }
    public String getPartsJson() { return partsJson; }
    public void setPartsJson(String partsJson) { this.partsJson = partsJson; }
    public Long getLastItemSeq() { return lastItemSeq; }
    public void setLastItemSeq(Long lastItemSeq) { this.lastItemSeq = lastItemSeq; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getOriginRef() { return originRef; }
    public void setOriginRef(String originRef) { this.originRef = originRef; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getParentTurnId() { return parentTurnId; }
    public void setParentTurnId(String parentTurnId) { this.parentTurnId = parentTurnId; }
    public Integer getDepth() { return depth; }
    public void setDepth(Integer depth) { this.depth = depth; }
}
