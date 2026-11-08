package com.tioka.connector.api.sdk;

import java.time.Instant;

/**
 * 连接器健康快照（CON-SDK）。
 *
 * @param status {@link ConnectorHealthStatus#ACTIVE} 或 {@link ConnectorHealthStatus#DEGRADED}
 */
public record ConnectorHealth(
        String status,
        Instant lastSuccessAt,
        String lastError,
        int consecutiveFailures
) {}
