package com.tioka.runtime.timeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code runtime_item} → 时间线 {@code TOOL} 来源事件（OBS-08 / ADR-075 §2「工具调用按步合并为一条」）。
 *
 * <p>同一步骤（{@code turn_id + step}）的工具调用与结果合并为一条：标题取自调用（工具名），
 * 结局与数据标签取自结果。{@code runtime_item} 不直接携带关联键（{@code request_id}/{@code subject} 在
 * {@code runtime_turn} 上），故由调用方注入 Turn 解析器补齐——投影按 {@code sourceEventId} 幂等。
 */
public class RuntimeItemTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "runtime_item";

    private static final String TOOL_CALL = ItemType.TOOL_CALL.wire();
    private static final String TOOL_RESULT = ItemType.TOOL_RESULT.wire();
    private static final String MDC_ACTOR = "工具调用";

    private final ObjectMapper mapper;

    public RuntimeItemTimelineProjection(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按步合并为 {@code TOOL} 条目（按输入顺序）。
     *
     * @param turnResolver 由 {@code turnId} 解析 Turn（补齐 {@code request_id}/{@code subject}），可为 {@code null}
     */
    public List<TimelineSourceEvent> toSourceEvents(List<RuntimeItemDO> items,
                                                    Function<String, RuntimeTurnDO> turnResolver) {
        Map<String, Group> groups = new LinkedHashMap<>();
        if (items != null) {
            for (RuntimeItemDO item : items) {
                String type = item.getType();
                boolean isCall = TOOL_CALL.equals(type);
                if (!isCall && !TOOL_RESULT.equals(type)) {
                    continue;
                }
                if (item.getTurnId() == null || item.getStep() == null) {
                    continue;
                }
                String key = item.getTurnId() + "|" + item.getStep();
                Group group = groups.computeIfAbsent(key,
                        k -> new Group(item.getTurnId(), item.getStep()));
                if (isCall) {
                    if (group.call == null) {
                        group.call = item;
                    }
                } else if (group.result == null) {
                    group.result = item;
                }
            }
        }
        List<TimelineSourceEvent> out = new ArrayList<>(groups.size());
        for (Group group : groups.values()) {
            out.add(toEvent(group, turnResolver));
        }
        return out;
    }

    /**
     * 单步（{@code turnId + step}）的合并事件；写入侧「写入即投影」按此取一条（OBS-08 偏离收口）。
     *
     * <p>与 {@link #toSourceEvents} 共用同一合并逻辑，故写入时条目与后续重建条目一致
     * （同 {@code sourceEventId}，投影幂等）。
     */
    public TimelineSourceEvent toSourceEvent(List<RuntimeItemDO> items, String turnId, Integer step,
                                             Function<String, RuntimeTurnDO> turnResolver) {
        if (turnId == null || step == null) {
            return null;
        }
        String key = SOURCE_TYPE + ":" + turnId + ":" + step;
        for (TimelineSourceEvent event : toSourceEvents(items, turnResolver)) {
            if (key.equals(event.sourceEventId())) {
                return event;
            }
        }
        return null;
    }

    private TimelineSourceEvent toEvent(Group group, Function<String, RuntimeTurnDO> turnResolver) {
        RuntimeItemDO anchor = group.call != null ? group.call : group.result;
        RuntimeTurnDO turn = turnResolver == null ? null : turnResolver.apply(group.turnId);
        String[] subject = splitSubject(turn == null ? null : turn.getSubject());

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "TOOL");
        attrs.put("title", MDC_ACTOR + "：" + toolName(group));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", anchor.getId());
        attrs.put("seq", group.step);
        if (group.result != null && group.result.getDataLabel() != null) {
            attrs.put("dataLabel", group.result.getDataLabel());
        }
        Boolean ok = group.result == null ? null : ok(group.result);
        if (ok != null) {
            attrs.put("outcome", ok ? "OK" : "ERROR");
        }
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + group.turnId + ":" + group.step,
                GovernanceMqTopics.RUNTIME_ITEM_RECORDED,
                turn == null ? null : turn.getRequestId(),
                null,
                subject[0],
                subject[1],
                null,
                occurredAt(anchor),
                attrs);
    }

    private String toolName(Group group) {
        JsonNode call = read(group.call);
        String name = call == null ? null : text(call, "name");
        if (name != null) {
            return name;
        }
        JsonNode result = read(group.result);
        String fallback = result == null ? null : text(result, "name");
        return fallback == null ? "?" : fallback;
    }

    private Boolean ok(RuntimeItemDO result) {
        JsonNode node = read(result);
        if (node == null || !node.has("ok") || !node.get("ok").isBoolean()) {
            return null;
        }
        return node.get("ok").asBoolean();
    }

    private JsonNode read(RuntimeItemDO item) {
        if (item == null || item.getContentJson() == null || item.getContentJson().isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(item.getContentJson());
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant occurredAt(RuntimeItemDO item) {
        LocalDateTime createAt = item == null ? null : item.getCreateAt();
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

    /** 一步的工具调用与结果。 */
    private static final class Group {
        private final String turnId;
        private final Integer step;
        private RuntimeItemDO call;
        private RuntimeItemDO result;

        private Group(String turnId, Integer step) {
            this.turnId = turnId;
            this.step = step;
        }
    }
}
