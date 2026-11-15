package com.tioka.governance.api.timeline;

import java.time.Instant;
import java.util.List;

/**
 * 时间线条目（ADR-075 §2 查询结果）。{@code summary} 不含 CONFIDENTIAL 原文；
 * 需要原文时下钻到 {@code sourceType} / {@code sourceId} 指回的原记录（受 {@code trajectory:read:content} 约束）。
 */
public record TimelineEntryView(
        String id,
        Instant occurredAt,
        int seq,
        String requestId,
        String parentRequestId,
        TimelineCategory category,
        String title,
        String summary,
        String actor,
        TimelineOutcome outcome,
        String sourceType,
        String sourceId,
        String traceId,
        String workspaceId,
        String dataLabel,
        List<TimelineSubjectRef> subjects) {
}
