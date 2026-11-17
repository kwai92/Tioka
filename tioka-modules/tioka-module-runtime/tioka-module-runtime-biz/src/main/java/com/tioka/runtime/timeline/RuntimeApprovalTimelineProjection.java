package com.tioka.runtime.timeline;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code runtime_approval} → 时间线 {@code APPROVAL} 来源事件（OBS-08 / ADR-075 §2）。
 *
 * <p>逐行投影（一单一条）：标题取审批标题（缺失回落工具名 / 类别），结局由状态映射
 * （{@code PENDING→WAITING}、{@code APPROVED→OK}、{@code REJECTED→DENIED}、{@code EXPIRED→ERROR}）。
 * 关联键（{@code request_id}/{@code subject}）随审批行写入，无需外部补齐——投影按 {@code sourceEventId} 幂等。
 */
public final class RuntimeApprovalTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "runtime_approval";

    public static TimelineSourceEvent toSourceEvent(RuntimeApprovalDO row) {
        if (row == null || row.getId() == null) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "APPROVAL");
        attrs.put("title", "审批：" + title(row));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        if (row.getRequestedBy() != null) {
            attrs.put("actor", row.getRequestedBy());
        }
        String outcome = outcome(row.getStatus());
        if (outcome != null) {
            attrs.put("outcome", outcome);
        }
        String[] subject = splitSubject(row.getSubject(), row.getSubjectType(), row.getSubjectId());
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.APPROVAL_RECORDED,
                row.getRequestId(),
                null,
                subject[0],
                subject[1],
                row.getWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    private static String title(RuntimeApprovalDO row) {
        if (row.getTitle() != null && !row.getTitle().isBlank()) {
            return row.getTitle();
        }
        if (row.getToolName() != null && !row.getToolName().isBlank()) {
            return row.getToolName();
        }
        return row.getKind() == null || row.getKind().isBlank() ? "?" : row.getKind();
    }

    private static String outcome(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return switch (ApprovalStatus.valueOf(status.toUpperCase())) {
                case PENDING -> "WAITING";
                case APPROVED -> "OK";
                case REJECTED -> "DENIED";
                case EXPIRED -> "ERROR";
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Instant occurredAt(RuntimeApprovalDO row) {
        LocalDateTime createAt = row.getCreateAt();
        return createAt == null ? Instant.now() : createAt.toInstant(ZoneOffset.UTC);
    }

    /**
     * 主体引用形如 {@code type:id}（ADR-073 / ADR-075 §6）。优先取 {@code subject} 列，
     * 缺失时回落 {@code subject_type} + {@code subject_id} 两列。
     */
    static String[] splitSubject(String subject, String subjectType, String subjectId) {
        if (subject != null && !subject.isBlank()) {
            int idx = subject.indexOf(':');
            if (idx > 0) {
                return new String[]{subject.substring(0, idx), subject.substring(idx + 1)};
            }
        }
        if (subjectType != null && !subjectType.isBlank() && subjectId != null && !subjectId.isBlank()) {
            return new String[]{subjectType, subjectId};
        }
        return new String[]{null, null};
    }

    private RuntimeApprovalTimelineProjection() {
    }
}
