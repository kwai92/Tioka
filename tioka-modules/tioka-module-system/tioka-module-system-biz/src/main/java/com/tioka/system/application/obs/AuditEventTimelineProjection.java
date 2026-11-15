package com.tioka.system.application.obs;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.system.infrastructure.persistence.AuditEventTimelineDO;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code audit_event} → 时间线 {@code SECURITY} 来源事件（OBS-08 / ADR-075 §2）。
 *
 * <p>只投影动作目录中 {@code category: SECURITY} 的动作（{@link SecurityAuditActions}）：
 * 其余审计动作已由各域来源投影（台账 / 运行项 / 模型用量 / 审批 / 变更 / 配置），
 * 若全量投影会重复计数。主体取审计记录上的 {@code subject}（ADR-073 §2），
 * 缺失时回落 {@code resource_type:resource_id}，使无请求键的管理类动作（如 API Key 生命周期）
 * 也能从主体时间线查到。投影按 {@code sourceEventId} 幂等。
 */
public final class AuditEventTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "audit_event";

    public static TimelineSourceEvent toSourceEvent(AuditEventTimelineDO row) {
        if (row == null || row.getId() == null || !SecurityAuditActions.isSecurity(row.getAction())) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "SECURITY");
        attrs.put("title", title(row));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        if (row.getActorId() != null && !row.getActorId().isBlank()) {
            attrs.put("actor", row.getActorId());
        }
        String outcome = outcome(row.getOutcome());
        if (outcome != null) {
            attrs.put("outcome", outcome);
        }
        if (row.getReason() != null && !row.getReason().isBlank()) {
            attrs.put("summary", row.getReason());
        }
        if (row.getTraceId() != null && !row.getTraceId().isBlank()) {
            attrs.put("traceId", row.getTraceId());
        }
        String[] subject = subject(row);
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.SECURITY_RECORDED,
                row.getRequestId(),
                null,
                subject[0],
                subject[1],
                row.getWorkspaceId(),
                row.getTs() == null ? Instant.now() : row.getTs(),
                attrs);
    }

    private static String title(AuditEventTimelineDO row) {
        StringBuilder sb = new StringBuilder("安全事件：").append(SecurityAuditActions.label(row.getAction()));
        if (row.getResourceId() != null && !row.getResourceId().isBlank()) {
            sb.append(' ').append(row.getResourceId());
        }
        return sb.toString();
    }

    /** 审计结局 → 时间线结局（ADR-017：SUCCESS 与业务同事务；DENIED / ERROR 独立事务）。 */
    static String outcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return null;
        }
        return switch (outcome) {
            case "SUCCESS" -> "OK";
            case "DENIED" -> "DENIED";
            case "ERROR" -> "ERROR";
            default -> null;
        };
    }

    /** 主体优先取审计记录上的 {@code subject}（ADR-073 §2），缺失回落资源引用。 */
    private static String[] subject(AuditEventTimelineDO row) {
        String[] explicit = splitSubject(row.getSubject());
        if (explicit[0] != null) {
            return explicit;
        }
        if (row.getResourceType() != null && row.getResourceId() != null) {
            return new String[]{row.getResourceType(), row.getResourceId()};
        }
        return new String[]{null, null};
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

    private AuditEventTimelineProjection() {
    }
}
