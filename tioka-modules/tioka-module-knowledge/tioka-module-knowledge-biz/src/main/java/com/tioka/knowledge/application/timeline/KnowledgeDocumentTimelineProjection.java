package com.tioka.knowledge.application.timeline;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentTimelineDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code kb_document} → 时间线 {@code CONFIG} 来源事件（OBS-12 / ADR-075 §4 知识文档主体时间线）。
 *
 * <p>逐行投影（一篇文档一条）：主体固定为 {@code knowledge_doc:<docId>}，条目标题带上入库状态与
 * 解析状态（{@code READY}/{@code PARTIAL}/{@code FAILED}），解析失败记 {@code ERROR}，
 * 失败原因落 {@code summary}，使管理员可从文档主体时间线查到「入库 → 解析」全过程。
 *
 * <p>文档数据标签原样透传到条目（{@code dataLabel}），交由查询侧按权限上限裁剪；
 * 空间从所属知识库（{@code kb_base}）带出。投影按 {@code sourceEventId} 幂等。
 */
public final class KnowledgeDocumentTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "kb_document";

    /** 主体类型：知识文档（ADR-075 §4）。 */
    public static final String SUBJECT_TYPE = "knowledge_doc";

    public static TimelineSourceEvent toSourceEvent(KbDocumentTimelineDO row) {
        if (row == null || row.getId() == null) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "CONFIG");
        attrs.put("title", title(row));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", row.getId());
        attrs.put("outcome", outcome(row));
        if (row.getDataLabel() != null && !row.getDataLabel().isBlank()) {
            attrs.put("dataLabel", row.getDataLabel());
        }
        if (row.getErrorMessage() != null && !row.getErrorMessage().isBlank()) {
            attrs.put("summary", row.getErrorMessage());
        }
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.CONFIG_RECORDED,
                null,
                null,
                SUBJECT_TYPE,
                row.getId(),
                row.getWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    private static String title(KbDocumentTimelineDO row) {
        StringBuilder sb = new StringBuilder("知识文档：");
        String name = row.getTitle();
        sb.append(name == null || name.isBlank() ? row.getId() : name);
        String state = state(row);
        if (state != null) {
            sb.append("（").append(state).append("）");
        }
        return sb.toString();
    }

    /** 人读状态：解析状态优先，回落入库状态。 */
    private static String state(KbDocumentTimelineDO row) {
        String parse = row.getParseStatus();
        if (parse != null && !parse.isBlank()) {
            return switch (parse.toUpperCase(Locale.ROOT)) {
                case "READY" -> "解析就绪";
                case "PARTIAL" -> "部分解析";
                case "FAILED" -> "解析失败";
                default -> parse;
            };
        }
        return row.getStatus();
    }

    /** 结局：解析或入库失败记 ERROR，其余 OK。 */
    static String outcome(KbDocumentTimelineDO row) {
        if ("FAILED".equalsIgnoreCase(row.getParseStatus())) {
            return "ERROR";
        }
        String status = row.getStatus();
        if (status != null && ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status))) {
            return "ERROR";
        }
        return "OK";
    }

    private static Instant occurredAt(KbDocumentTimelineDO row) {
        LocalDateTime at = row.getOccurredAt();
        return at == null ? Instant.now() : at.toInstant(ZoneOffset.UTC);
    }

    private KnowledgeDocumentTimelineProjection() {
    }
}
