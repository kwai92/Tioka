package com.tioka.governance.application.timeline;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.governance.infrastructure.persistence.RunManifestDO;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行清单 → 时间线 {@code RUN} 来源事件（OBS-06 → OBS-08 / ADR-075 §2、§3）。
 *
 * <p>写入（{@code DefaultRunManifestWriter}）与重建（{@link RunManifestTimelineRebuildSource}）
 * 共用同一映射，保证两侧条目一致——投影按 {@code sourceEventId} 幂等，重建可覆盖写入时的近似时刻。
 */
public final class RunManifestTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "run_manifest";

    public static TimelineSourceEvent toSourceEvent(RunManifestDO row) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "RUN");
        attrs.put("title", "运行开始：" + row.getRunKind());
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        attrs.put("seq", row.getSeq());
        if (row.getTraceId() != null) {
            attrs.put("traceId", row.getTraceId());
        }
        String[] subject = splitSubject(row.getSubject());
        return new TimelineSourceEvent(
                row.getId(),
                GovernanceMqTopics.RUN_MANIFEST_WRITTEN,
                row.getRequestId(),
                row.getParentRequestId(),
                subject[0],
                subject[1],
                row.getWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    /** 写入时 {@code create_at} 由列默认值生成，回落到当前时刻（重建时读回真实值）。 */
    private static Instant occurredAt(RunManifestDO row) {
        return row.getCreateAt() == null ? Instant.now() : row.getCreateAt().toInstant(ZoneOffset.UTC);
    }

    /** 主体引用形如 {@code type:id}（ADR-073 / ADR-075 §6）；无冒号视为无类型主体。 */
    private static String[] splitSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return new String[]{null, null};
        }
        int idx = subject.indexOf(':');
        return idx > 0 ? new String[]{subject.substring(0, idx), subject.substring(idx + 1)}
                : new String[]{null, null};
    }

    private RunManifestTimelineProjection() {
    }
}
