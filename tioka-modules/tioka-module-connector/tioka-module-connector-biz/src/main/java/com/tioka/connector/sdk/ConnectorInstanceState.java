package com.tioka.connector.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.connector.api.sdk.ConnectorHealth;
import com.tioka.connector.api.sdk.ConnectorHealthStatus;

import java.time.Instant;

/**
 * 连接器实例运行时状态（内存仓与持久化 DO 的公共投影）。
 */
final class ConnectorInstanceState {

    private final String id;
    private final String workspaceId;
    private final String connectorType;
    private final String name;
    private final JsonNode config;
    private final double rateLimitQps;
    private String credentialRef;
    private String credentialId;
    private String syncCursor;
    private String healthStatus = ConnectorHealthStatus.ACTIVE;
    private Instant lastSuccessAt;
    private String lastError;
    private int consecutiveFailures;

    ConnectorInstanceState(String id,
                           String workspaceId,
                           String connectorType,
                           String name,
                           JsonNode config,
                           double rateLimitQps) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.connectorType = connectorType;
        this.name = name;
        this.config = config;
        this.rateLimitQps = rateLimitQps;
    }

    String id() {
        return id;
    }

    String workspaceId() {
        return workspaceId;
    }

    String connectorType() {
        return connectorType;
    }

    String name() {
        return name;
    }

    JsonNode config() {
        return config;
    }

    double rateLimitQps() {
        return rateLimitQps;
    }

    String credentialRef() {
        return credentialRef;
    }

    void setCredentialRef(String credentialRef, String credentialId) {
        this.credentialRef = credentialRef;
        this.credentialId = credentialId;
    }

    String credentialId() {
        return credentialId;
    }

    String syncCursor() {
        return syncCursor;
    }

    void setSyncCursor(String syncCursor) {
        this.syncCursor = syncCursor;
    }

    ConnectorHealth toHealth() {
        return new ConnectorHealth(healthStatus, lastSuccessAt, lastError, consecutiveFailures);
    }

    void recordSuccess(Instant at) {
        this.lastSuccessAt = at;
        this.lastError = null;
        this.consecutiveFailures = 0;
        this.healthStatus = ConnectorHealthStatus.ACTIVE;
    }

    void recordFailure(String error, Instant at) {
        this.lastError = error;
        this.consecutiveFailures++;
        if (this.consecutiveFailures >= ConnectorHealthStatus.DEGRADED_FAILURE_THRESHOLD) {
            this.healthStatus = ConnectorHealthStatus.DEGRADED;
        }
    }
}
