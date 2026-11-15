package com.tioka.model.application.timeline;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code model_usage} → 时间线 {@code MODEL} 来源事件（OBS-08 / ADR-075 §2「模型调用按步合并为一条」）。
 *
 * <p>Turn 内的模型调用不在外部交互台账（台账只覆盖非 Turn 调用），其时间线条目由本映射补齐。
 * 逐行投影（一行一次调用），重建可覆盖写入时的近似时刻——投影按 {@code sourceEventId} 幂等。
 */
public final class ModelUsageTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "model_usage";

    public static TimelineSourceEvent toSourceEvent(ModelUsageDO row) {
        if (row == null || row.getId() == null) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "MODEL");
        attrs.put("title", "模型调用：" + (row.getModelRef() == null ? "?" : row.getModelRef()));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        if (row.getStep() != null) {
            attrs.put("seq", row.getStep());
        }
        if (row.getTraceId() != null) {
            attrs.put("traceId", row.getTraceId());
        }
        String outcome = outcome(row.getStatus());
        if (outcome != null) {
            attrs.put("outcome", outcome);
        }
        String[] subject = splitSubject(row.getSubject());
        return new TimelineSourceEvent(
                row.getId(),
                GovernanceMqTopics.MODEL_USAGE_RECORDED,
                row.getRequestId(),
                null,
                subject[0],
                subject[1],
                row.getWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    private static String outcome(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return "OK".equalsIgnoreCase(status) ? "OK" : "ERROR";
    }

    private static Instant occurredAt(ModelUsageDO row) {
        LocalDateTime createAt = row.getCreateAt();
        return createAt == null ? Instant.now() : createAt.toInstant(ZoneOffset.UTC);
    }

    /** 主体引用形如 {@code type:id}（ADR-073 / ADR-075 §6）。 */
    static String[] splitSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return new String[]{null, null};
        }
        int idx = subject.indexOf(':');
        return idx > 0 ? new String[]{subject.substring(0, idx), subject.substring(idx + 1)}
                : new String[]{null, null};
    }

    private ModelUsageTimelineProjection() {
    }
}
