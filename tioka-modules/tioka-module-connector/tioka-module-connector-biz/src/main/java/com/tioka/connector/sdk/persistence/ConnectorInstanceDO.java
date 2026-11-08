package com.tioka.connector.sdk.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

import java.time.Instant;

/**
 * 连接器实例（V216 {@code con_connector_instance}）。无 owner_id，不继承 WorkspaceAwareDO。
 */
@TableName("con_connector_instance")
public class ConnectorInstanceDO extends BaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String connectorType;
    private String name;
    private String configJson;
    private String credentialRef;
    private String syncCursor;
    private String healthStatus;
    private Instant lastSuccessAt;
    private String lastError;
    private Integer consecutiveFailures;
    private Double rateLimitQps;
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getConnectorType() {
        return connectorType;
    }

    public void setConnectorType(String connectorType) {
        this.connectorType = connectorType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public String getSyncCursor() {
        return syncCursor;
    }

    public void setSyncCursor(String syncCursor) {
        this.syncCursor = syncCursor;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Integer getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(Integer consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Double getRateLimitQps() {
        return rateLimitQps;
    }

    public void setRateLimitQps(Double rateLimitQps) {
        this.rateLimitQps = rateLimitQps;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
