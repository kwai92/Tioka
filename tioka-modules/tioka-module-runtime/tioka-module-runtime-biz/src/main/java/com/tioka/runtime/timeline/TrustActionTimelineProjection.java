package com.tioka.runtime.timeline;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code trust_action} → 时间线 {@code CHANGE} 来源事件（OBS-08 / ADR-075 §2「变更单、提交、撤销」）。
 *
 * <p>逐行投影（一动作一条）：标题取工具名（缺失回落动作类别），撤销 / 补偿动作前缀「撤销」；
 * 结局由状态映射（{@code PROPOSED/COMPENSATING→WAITING}、{@code APPROVED/APPLIED/COMPENSATED→OK}、
 * {@code COMPENSATION_FAILED→ERROR}）；撤销原因进 {@code summary}。
 * {@code trust_action} 不直接携带关联键（{@code request_id}/{@code subject} 在 {@code runtime_turn} 上），
 * 故由调用方注入 Turn 解析器补齐——投影按 {@code sourceEventId} 幂等。
 */
public final class TrustActionTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "trust_action";

    private static final String TITLE_CHANGE = "变更：";
    private static final String TITLE_UNDO = "撤销：";

    /**
     * 投影一条变更动作。
     *
     * @param turnResolver 由 {@code turnId} 解析 Turn（补齐 {@code request_id}/{@code subject}），可为 {@code null}
     */
    public static TimelineSourceEvent toSourceEvent(TrustActionDO row,
                                                    Function<String, RuntimeTurnDO> turnResolver) {
        if (row == null || row.getId() == null) {
            return null;
        }
        RuntimeTurnDO turn = (turnResolver == null || row.getTurnId() == null)
                ? null : turnResolver.apply(row.getTurnId());
        String[] subject = splitSubject(turn == null ? null : turn.getSubject());

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "CHANGE");
        attrs.put("title", title(row));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        if (row.getSeq() != null) {
            attrs.put("seq", row.getSeq());
        }
        if (row.getUndoneBy() != null && !row.getUndoneBy().isBlank()) {
            attrs.put("actor", row.getUndoneBy());
        }
        String outcome = outcome(row.getStatus());
        if (outcome != null) {
            attrs.put("outcome", outcome);
        }
        if (row.getUndoReason() != null && !row.getUndoReason().isBlank()) {
            attrs.put("summary", row.getUndoReason());
        }
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.CHANGE_RECORDED,
                turn == null ? null : turn.getRequestId(),
                null,
                subject[0],
                subject[1],
                row.getWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    private static String title(TrustActionDO row) {
        String name = row.getToolName();
        if (name == null || name.isBlank()) {
            name = row.getActionClass() == null || row.getActionClass().isBlank() ? "?" : row.getActionClass();
        }
        return (isCompensating(row.getStatus()) ? TITLE_UNDO : TITLE_CHANGE) + name;
    }

    private static boolean isCompensating(String status) {
        return PreparedCallStore.STATUS_COMPENSATING.equals(status)
                || PreparedCallStore.STATUS_COMPENSATED.equals(status)
                || PreparedCallStore.STATUS_COMPENSATION_FAILED.equals(status);
    }

    private static String outcome(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (PreparedCallStore.STATUS_PROPOSED.equals(status)
                || PreparedCallStore.STATUS_COMPENSATING.equals(status)) {
            return "WAITING";
        }
        if (PreparedCallStore.STATUS_APPROVED.equals(status)
                || PreparedCallStore.STATUS_APPLIED.equals(status)
                || PreparedCallStore.STATUS_COMPENSATED.equals(status)) {
            return "OK";
        }
        if (PreparedCallStore.STATUS_COMPENSATION_FAILED.equals(status)) {
            return "ERROR";
        }
        return null;
    }

    /** 变更动作以「最后状态时间」为准（未流转的动作回落创建时间）。 */
    private static Instant occurredAt(TrustActionDO row) {
        LocalDateTime at = row.getUpdateAt() == null ? row.getCreateAt() : row.getUpdateAt();
        return at == null ? Instant.now() : at.toInstant(ZoneOffset.UTC);
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

    private TrustActionTimelineProjection() {
    }
}
