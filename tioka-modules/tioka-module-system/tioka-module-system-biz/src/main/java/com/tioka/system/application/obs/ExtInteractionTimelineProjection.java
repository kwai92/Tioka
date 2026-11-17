package com.tioka.system.application.obs;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.system.api.obs.ExtInteractionView;
import com.tioka.system.infrastructure.persistence.ExtInteractionWriteRow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部交互台账 → 时间线来源事件（OBS-07 → OBS-08 / ADR-075 §2）。
 *
 * <p>台账是「跨边界观测面」：入站收敛为 {@code INBOUND}；出站按 {@code kind} 收敛为
 * {@code MODEL}（模型）/ {@code TOOL}（MCP、A2A、外部知识库）/ {@code NOTIFY}（通知）/ {@code OUTBOUND}（其余）。
 * 写入（{@code DefaultExtInteractionRecorder}）与重建（{@link ExtInteractionTimelineRebuildSource}）共用本映射，
 * 两侧条目一致——投影按 {@code sourceEventId} 幂等。
 */
public final class ExtInteractionTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "ext_interaction";

    /** 写入侧：直接由落库行投影（避免二次读回）。 */
    public static TimelineSourceEvent toSourceEvent(ExtInteractionWriteRow row) {
        if (row == null) {
            return null;
        }
        return event(row.id(), row.ts(), row.direction(), row.kind(), row.counterparty(),
                row.endpoint(), row.operation(), row.actorId(), row.traceId(), row.requestId(),
                row.subject(), row.status(), row.dataLabel());
    }

    /** 重建侧：由只读查询视图投影。 */
    public static TimelineSourceEvent toSourceEvent(ExtInteractionView view) {
        if (view == null) {
            return null;
        }
        return event(view.id(), view.ts(), view.direction(), view.kind(), view.counterparty(),
                view.endpoint(), view.operation(), view.actorId(), view.traceId(), view.requestId(),
                view.subject(), view.status(), view.dataLabel());
    }

    private static TimelineSourceEvent event(String id, Instant ts, String direction, String kind,
                                             String counterparty, String endpoint, String operation,
                                             String actorId, String traceId, String requestId,
                                             String subject, String status, String dataLabel) {
        String category = category(direction, kind);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", category);
        attrs.put("title", title(category, kind, counterparty));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", id);
        if (traceId != null) {
            attrs.put("traceId", traceId);
        }
        if (dataLabel != null) {
            attrs.put("dataLabel", dataLabel);
        }
        if (actorId != null) {
            attrs.put("actor", actorId);
        }
        String outcome = outcome(status);
        if (outcome != null) {
            attrs.put("outcome", outcome);
        }
        if (endpoint != null || operation != null) {
            attrs.put("summary", summary(direction, kind, counterparty, endpoint, operation));
        }
        String[] split = splitSubject(subject);
        return new TimelineSourceEvent(
                id,
                GovernanceMqTopics.EXT_INTERACTION_RECORDED,
                requestId,
                null,
                split[0],
                split[1],
                null,
                ts == null ? Instant.now() : ts,
                attrs);
    }

    /** 台账方向 / 类别 → 时间线类别（ADR-075 §2 的 12 类）。 */
    static String category(String direction, String kind) {
        String k = kind == null ? "" : kind;
        if ("IN".equalsIgnoreCase(direction)) {
            return "INBOUND";
        }
        return switch (k) {
            case "model" -> "MODEL";
            case "mcp", "a2a", "ext_kb" -> "TOOL";
            case "notify" -> "NOTIFY";
            default -> "OUTBOUND";
        };
    }

    private static String title(String category, String kind, String counterparty) {
        String to = counterparty == null ? "" : " → " + counterparty;
        return switch (category) {
            case "INBOUND" -> "入站：" + kind;
            case "MODEL" -> "模型调用" + to;
            case "TOOL" -> "工具调用：" + kind + to;
            case "NOTIFY" -> "通知" + to;
            default -> "外发：" + kind + to;
        };
    }

    private static String summary(String direction, String kind, String counterparty,
                                  String endpoint, String operation) {
        String op = operation == null ? "" : operation;
        String ep = endpoint == null ? "" : endpoint;
        String cp = counterparty == null ? "" : counterparty;
        return (direction == null ? "" : direction) + " " + kind + " " + cp + " " + op + " " + ep;
    }

    private static String outcome(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return "OK".equalsIgnoreCase(status) ? "OK" : "ERROR";
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

    private ExtInteractionTimelineProjection() {
    }
}
