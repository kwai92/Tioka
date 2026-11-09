package com.tioka.system.infrastructure.persistence;

import java.time.Instant;

/**
 * 外部交互台账落库行（OBS-04a / ADR-074 §1 全字段）。
 * 关联键超出固定列的部分进 {@code attrsJson}；payload 缺省只存哈希与大小。
 */
public record ExtInteractionWriteRow(
        String id,
        Instant ts,
        String direction,
        String kind,
        String counterparty,
        String endpoint,
        String operation,
        String authMode,
        String credentialId,
        String actorId,
        String runAs,
        String traceId,
        String requestId,
        String subject,
        String turnId,
        String runId,
        String changeSetId,
        String toolCallId,
        String idempotencyKey,
        String externalRef,
        String dedupeKey,
        String requestDigest,
        String responseDigest,
        Long requestSize,
        Long responseSize,
        String payloadRef,
        String dataLabel,
        String status,
        Integer httpStatus,
        String errorCode,
        Integer attempt,
        Long latencyMs,
        String retentionClass,
        String attrsJson
) {}
