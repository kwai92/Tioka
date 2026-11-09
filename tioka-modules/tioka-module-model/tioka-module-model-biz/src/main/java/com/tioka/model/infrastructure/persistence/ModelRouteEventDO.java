package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

/**
 * MH-06 路由事件时间线（V214 {@code model_route_event}；ADR-024「一次选模」每步一条；只增不改）。
 * kind：DECIDE / SELECT / REUSE / FAILOVER / FAIL。
 */
@TableName("model_route_event")
public class ModelRouteEventDO extends AppendOnlyDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String threadId;
    private String turnId;
    private String planId;
    private Integer planVersionNo;
    private String kind;
    private String mode;
    private String deciderId;
    private String decidedGroup;
    private String selectedBindingId;
    private Double confidence;
    private String reason;
    private Integer latencyMs;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public Integer getPlanVersionNo() { return planVersionNo; }
    public void setPlanVersionNo(Integer planVersionNo) { this.planVersionNo = planVersionNo; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getDeciderId() { return deciderId; }
    public void setDeciderId(String deciderId) { this.deciderId = deciderId; }
    public String getDecidedGroup() { return decidedGroup; }
    public void setDecidedGroup(String decidedGroup) { this.decidedGroup = decidedGroup; }
    public String getSelectedBindingId() { return selectedBindingId; }
    public void setSelectedBindingId(String selectedBindingId) { this.selectedBindingId = selectedBindingId; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
}
