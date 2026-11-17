package com.tioka.eval.timeline;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.eval.infrastructure.persistence.EvalFeedbackDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code eval_feedback} → 时间线 {@code FEEDBACK} 来源事件（OBS-08 / ADR-075 §2「反馈项」）。
 *
 * <p>逐行投影（一条反馈一个条目）：标题按 {@code rating} + {@code source} 生成
 * （点赞 / 点踩，撤销与法务驳回带来源后缀），正文取意见。{@code eval_feedback} 带
 * {@code turn_id} 但不带空间与请求（{@code workspace-scope-exempt}，V212），主体固定为
 * {@code turn:{turnId}}；投影按 {@code sourceEventId} 幂等。
 */
public final class EvalFeedbackTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "eval_feedback";

    public static TimelineSourceEvent toSourceEvent(EvalFeedbackDO row) {
        if (row == null || row.getId() == null) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "FEEDBACK");
        attrs.put("title", "反馈：" + rating(row.getRating()) + suffix(row.getSource()));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        attrs.put("actor", row.getUserId() == null ? "system" : row.getUserId());
        if (row.getComment() != null && !row.getComment().isBlank()) {
            attrs.put("summary", row.getComment());
        }
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.FEEDBACK_RECORDED,
                null,
                null,
                "turn",
                row.getTurnId(),
                null,
                occurredAt(row),
                attrs);
    }

    private static String rating(Integer rating) {
        if (rating == null) {
            return "记录";
        }
        return rating > 0 ? "点赞" : rating < 0 ? "点踩" : "记录";
    }

    /** 来源后缀：撤销（TRU-07）/ 法务驳回与人工反馈区分（EVO-01 / ADR-031）。 */
    private static String suffix(String source) {
        if ("UNDO".equalsIgnoreCase(source)) {
            return "（撤销）";
        }
        if ("REVIEW".equalsIgnoreCase(source)) {
            return "（法务驳回）";
        }
        return "";
    }

    private static Instant occurredAt(EvalFeedbackDO row) {
        LocalDateTime createAt = row.getCreateAt();
        return createAt == null ? Instant.now() : createAt.toInstant(ZoneOffset.UTC);
    }

    private EvalFeedbackTimelineProjection() {
    }
}
