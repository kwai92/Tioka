package com.tioka.orchestration.timeline;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.orchestration.api.engine.DecisionCode;
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionTimelineDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code orch_decision} → 时间线 {@code DECISION} 来源事件（OBS-08 / ADR-075 §2、§6）。
 *
 * <p>逐行投影（一次规则求值一条）：标题取决定码，结局按 {@link DecisionCode} 映射
 * （{@code ACCEPT}/{@code WOULD_ACCEPT}/{@code IGNORE → OK}、{@code STOP → DENIED}、
 * {@code UNAVAILABLE → ERROR}）。{@code orch_decision} 不带主体与空间，关联键经 {@code event_id}
 * 左连 {@code orch_event} 取 {@code subject_key}/{@code workspace_id}（行内已补，见
 * {@code OrchDecisionMapper.listByRange}）。投影按 {@code sourceEventId} 幂等。
 */
public final class OrchDecisionTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "orch_decision";

    public static TimelineSourceEvent toSourceEvent(OrchDecisionTimelineDO row) {
        if (row == null || row.getId() == null) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "DECISION");
        attrs.put("title", "规则求值：" + decision(row.getDecision()));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        attrs.put("actor", "system");
        if (row.getReason() != null && !row.getReason().isBlank()) {
            attrs.put("summary", row.getReason());
        }
        String outcome = outcome(row.getDecision());
        if (outcome != null) {
            attrs.put("outcome", outcome);
        }
        String[] subject = splitSubject(row.getSubjectKey());
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.DECISION_RECORDED,
                null,
                null,
                subject[0],
                subject[1],
                row.getWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    private static String decision(String raw) {
        return raw == null || raw.isBlank() ? "?" : raw;
    }

    /** 决定码 → 时间线结局（ADR-075 §2）。{@code STOP} 为授权书撤销导致的停止，记 {@code DENIED}。 */
    private static String outcome(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return switch (DecisionCode.valueOf(raw.toUpperCase())) {
                case ACCEPT, WOULD_ACCEPT, IGNORE -> "OK";
                case STOP -> "DENIED";
                case UNAVAILABLE -> "ERROR";
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Instant occurredAt(OrchDecisionTimelineDO row) {
        LocalDateTime createAt = row.getCreateAt();
        return createAt == null ? Instant.now() : createAt.toInstant(ZoneOffset.UTC);
    }

    /** 主体引用形如 {@code type:id}（ADR-073 / ADR-075 §6）；无冒号视为无类型主体。 */
    static String[] splitSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return new String[]{null, null};
        }
        int idx = subject.indexOf(':');
        return idx > 0 ? new String[]{subject.substring(0, idx), subject.substring(idx + 1)}
                : new String[]{null, null};
    }

    private OrchDecisionTimelineProjection() {
    }
}
