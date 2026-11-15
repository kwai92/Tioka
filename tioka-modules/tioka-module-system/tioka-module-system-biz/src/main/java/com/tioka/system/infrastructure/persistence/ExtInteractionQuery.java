package com.tioka.system.infrastructure.persistence;

import java.time.Instant;

/**
 * 外部交互台账查询条件（OBS-07 / ADR-074 §4）。
 * 各维度为 {@code null} 时不过滤；台账无租户列，故不含 workspaceId。
 */
public record ExtInteractionQuery(
        String requestId,
        String subject,
        String counterparty,
        String externalRef,
        String kind,
        String direction,
        Instant from,
        Instant to,
        int limit,
        int offset) {
}
