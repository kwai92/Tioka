package com.tioka.connector.api.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 登记连接器实例（不含明文凭证；凭证经 {@link ConnectorSdk#bindCredential} 写入引用）。
 */
public record ConnectorInstanceRegistration(
        String workspaceId,
        String connectorType,
        String name,
        JsonNode config,
        double rateLimitQps
) {
    public ConnectorInstanceRegistration {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 必填");
        }
        if (connectorType == null || connectorType.isBlank()) {
            throw new IllegalArgumentException("connectorType 必填");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        if (rateLimitQps <= 0) {
            rateLimitQps = 5.0;
        }
    }

    public ConnectorInstanceRegistration(String workspaceId, String connectorType, String name, JsonNode config) {
        this(workspaceId, connectorType, name, config, 5.0);
    }
}
