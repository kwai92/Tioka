package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

@TableName("runtime_thread")
public class RuntimeThreadDO extends WorkspaceAwareDO {
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
    /** @deprecated 历史 Part 投影；列已删，仅内存兼容 */
    @Deprecated
    @TableField(exist = false)
    private String partsJson;
    private Long lastItemSeq;
    private String origin;
    private String originRef;
    private String metadataJson;
    private String parentTurnId;
    private Integer depth;
    /** MAS-01：当前生效的委派授予 ID（V160）。 */
    private String grantId;
    /** DAT-02（V164）：Turn 级数据标签，MONOTONIC 只升不降（ADR-040）。 */
    private String dataLabel;

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
    @Deprecated
    public String getPartsJson() { return partsJson; }
    @Deprecated
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
    public String getGrantId() { return grantId; }
    public void setGrantId(String grantId) { this.grantId = grantId; }
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
}
