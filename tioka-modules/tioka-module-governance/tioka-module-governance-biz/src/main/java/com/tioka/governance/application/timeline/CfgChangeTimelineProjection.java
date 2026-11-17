package com.tioka.governance.application.timeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.governance.infrastructure.persistence.CfgChangeDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code cfg_change} → 时间线 {@code CONFIG} 来源事件（OBS-08 / ADR-075 §2 配置变更背景条目）。
 *
 * <p>逐行投影（一条变更一条）：主体取配置对象本身（{@code object_type} 归一为小写点分名，
 * 如 {@code MODEL_ALIAS} → {@code model.alias}；{@code target_key} 为对象标识），
 * 因此条目落到该主体的时间线上作为背景（灰色）条目（ADR-075 §5「配置对象本身作为 subject」）。
 * 标题按 {@code after_json.action} 区分发布 / 回滚，达不到关联请求（配置变更是平台级动作），
 * 故 {@code request_id} 与 {@code workspace_id} 为空——投影按 {@code sourceEventId} 幂等。
 */
public final class CfgChangeTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "cfg_change";

    public static TimelineSourceEvent toSourceEvent(CfgChangeDO row, ObjectMapper mapper) {
        if (row == null || row.getId() == null) {
            return null;
        }
        String subjectType = normalizeSubjectType(row.getObjectType());
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "CONFIG");
        attrs.put("title", title(row, subjectType, mapper));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", String.valueOf(row.getId()));
        attrs.put("outcome", "OK");
        if (row.getActorId() != null && !row.getActorId().isBlank()) {
            attrs.put("actor", row.getActorId());
        }
        if (row.getReason() != null && !row.getReason().isBlank()) {
            attrs.put("summary", row.getReason());
        }
        if (row.getTraceId() != null && !row.getTraceId().isBlank()) {
            attrs.put("traceId", row.getTraceId());
        }
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.CONFIG_RECORDED,
                null,
                null,
                subjectType,
                row.getTargetKey(),
                null,
                occurredAt(row),
                attrs);
    }

    /** 配置对象类型归一为小写点分名（ADR-075 §5 例：{@code model.alias:sys.chat}）。 */
    static String normalizeSubjectType(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            return null;
        }
        return objectType.trim().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static String title(CfgChangeDO row, String subjectType, ObjectMapper mapper) {
        StringBuilder sb = new StringBuilder("配置变更：");
        sb.append(subjectType == null ? "?" : subjectType);
        if (row.getTargetKey() != null && !row.getTargetKey().isBlank()) {
            sb.append(' ').append(row.getTargetKey());
        }
        String action = action(row, mapper);
        if (action != null) {
            sb.append("（").append(action).append("）");
        }
        return sb.toString();
    }

    /** 读 {@code after_json.action}（发布 / 回滚）与版本号，拼成人读后缀；解析失败即忽略。 */
    private static String action(CfgChangeDO row, ObjectMapper mapper) {
        if (mapper == null || row.getAfterJson() == null || row.getAfterJson().isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = mapper.readTree(row.getAfterJson());
        } catch (Exception ignored) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode rawAction = node.get("action");
        String verb = rawAction == null || rawAction.isNull() ? null : switch (rawAction.asText()) {
            case "publish" -> "发布";
            case "rollback" -> "回滚";
            case "immediate" -> "即时改动";
            default -> rawAction.asText();
        };
        if (verb == null) {
            return null;
        }
        JsonNode versionNo = node.get("versionNo");
        return versionNo == null || versionNo.isNull() ? verb : verb + " v" + versionNo.asText();
    }

    private static Instant occurredAt(CfgChangeDO row) {
        LocalDateTime createdAt = row.getCreatedAt();
        return createdAt == null ? Instant.now() : createdAt.toInstant(ZoneOffset.UTC);
    }

    private CfgChangeTimelineProjection() {
    }
}
