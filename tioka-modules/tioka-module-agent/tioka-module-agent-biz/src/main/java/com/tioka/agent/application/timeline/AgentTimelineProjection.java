package com.tioka.agent.application.timeline;

import com.tioka.agent.infrastructure.persistence.AgentVersionTimelineDO;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code agent_version} → 时间线 {@code CONFIG} 来源事件（OBS-12 / ADR-075 §4 Agent 主体时间线）。
 *
 * <p>逐版本投影（一个版本一条）：主体固定为 {@code agent:<agentId>}，条目标题带版本号与上线状态
 * （{@code PUBLISHED}/{@code RETIRED}），使管理员可从 Agent 主体时间线查到「版本 → 上线/下线」演进。
 * 发布人落 {@code actor}，版本说明（note）落 {@code summary}。空间从所属 Agent 带出。
 * 投影按 {@code sourceEventId} 幂等。
 */
public final class AgentTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "agent_version";

    /** 主体类型：Agent（ADR-075 §4）。 */
    public static final String SUBJECT_TYPE = "agent";

    public static TimelineSourceEvent toSourceEvent(AgentVersionTimelineDO row) {
        if (row == null || row.getId() == null || row.getAgentId() == null) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "CONFIG");
        attrs.put("title", title(row));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        attrs.put("outcome", "OK");
        if (row.getPublishedBy() != null && !row.getPublishedBy().isBlank()) {
            attrs.put("actor", row.getPublishedBy());
        }
        if (row.getNote() != null && !row.getNote().isBlank()) {
            attrs.put("summary", row.getNote());
        }
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.CONFIG_RECORDED,
                null,
                null,
                SUBJECT_TYPE,
                row.getAgentId(),
                row.getWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    private static String title(AgentVersionTimelineDO row) {
        StringBuilder sb = new StringBuilder("Agent 版本：");
        sb.append(row.getVersionNo() == null ? "?" : "v" + row.getVersionNo());
        String state = state(row.getStatus());
        if (state != null) {
            sb.append("（").append(state).append("）");
        }
        return sb.toString();
    }

    /** 上线状态人读名（{@code AgentService} 状态字面量）。 */
    static String state(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PUBLISHED" -> "已发布";
            case "RETIRED" -> "已下线";
            default -> status;
        };
    }

    private static Instant occurredAt(AgentVersionTimelineDO row) {
        LocalDateTime at = row.getOccurredAt();
        return at == null ? Instant.now() : at.toInstant(ZoneOffset.UTC);
    }

    private AgentTimelineProjection() {
    }
}
