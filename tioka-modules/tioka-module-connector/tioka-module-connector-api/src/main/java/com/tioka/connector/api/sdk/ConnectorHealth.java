package com.tioka.connector.api.sdk;

import java.time.Instant;

/**
 * 连接器健康快照（CON-SDK）。
 */
public record ConnectorHealth(
        String status,
        Instant lastSuccessAt,
        String lastError,
        int consecutiveFailures
) {}
