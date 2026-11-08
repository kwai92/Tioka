package com.tioka.governance.api.timeline;

import java.time.Instant;
import java.util.Map;

/**
 * 时间线来源事件（ADR-075 / OBS-08）。各域发布后由投影消费者写入 timeline_entry。
 */
public record TimelineSourceEvent(
        String eventType,
        String requestId,
        String subjectType,
        String subjectId,
        String workspaceId,
        Instant occurredAt,
        Map<String, Object> attrs
) {}
