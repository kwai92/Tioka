package com.tioka.governance.infrastructure.persistence;

import java.time.LocalDateTime;

/**
 * {@code cfg_change}（V150 / ADR-041 §3）：配置对象的变更记录（改前、改后、谁、为什么）。
 *
 * <p>无逻辑删、无 {@code workspace_id}：变更记录是平台级配置历史（ADR-041 第 3 条，
 * 配置归各自领域）。时间列为 {@code created_at}（非 {@code create_at}）。
 */
public class CfgChangeDO {
    private Long id;
    private String objectType;
    private String targetKey;
    private String beforeJson;
    private String afterJson;
    private String actorId;
    private String reason;
    private LocalDateTime createdAt;
    private String traceId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String targetKey) { this.targetKey = targetKey; }
    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }
    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
