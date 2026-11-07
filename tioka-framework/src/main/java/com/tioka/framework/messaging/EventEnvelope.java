package com.tioka.framework.messaging;

import java.util.Map;

/**
 * 跨域 MQ 信封（ORC-02 / OBS-03；契约见 {@code docs/contracts/events/mq-envelope-v1.json}）。
 *
 * <p>不含 {@code tenantId}（ADR-029 第 60 条 / 约束 F28）。
 */
public record EventEnvelope(
        String eventId,
        String event,
        int schemaVersion,
        String workspaceId,
        String occurredAt,
        String producer,
        String traceId,
        String traceparent,
        String replayOf,
        String requestId,
        String subject,
        String subjectKey,
        Integer hops,
        RunAs runAs,
        Map<String, Object> payload) {

    /** 身份片段；不含权限集（ADR-029 第 6 条）。 */
    public record RunAs(String userId, String workspaceId, String type) {}
}
