package com.tioka.runtime.kernel.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * item → OpenAI messages。status/error/reasoning 不进上下文。
 * P3-00b / K1a：可注入 {@link ContextSegmentRegistry}；空注册表时组装路径与原先一致。
 */
public final class ContextBuilder {
    private final ObjectMapper mapper;
    private final ContextSegmentRegistry segments;

    public ContextBuilder(ObjectMapper mapper) {
        this(mapper, new ContextSegmentRegistry(List.of()));
    }

    public ContextBuilder(ObjectMapper mapper, ContextSegmentRegistry segments) {
        this.mapper = mapper;
        this.segments = segments == null ? new ContextSegmentRegistry(List.of()) : segments;
    }

    /** 段提供者注册表（RT-10 / CTX-01 消费；P3-00b 仅挂接，不改现有 item 组装）。 */
    public ContextSegmentRegistry segments() {
        return segments;
    }

    public List<Map<String, Object>> build(List<RuntimeItemDO> items) {
        // 空注册表：不插入额外段，行为 = M2 现状
        if (!segments.isEmpty()) {
            // 占位：后续 RT-10 按 zone/order 调提供者；当前无实现 bean，不会进入
            segments.providers();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<RuntimeItemDO> pendingToolCalls = new ArrayList<>();
        Integer pendingStep = null;

        for (RuntimeItemDO item : items) {
            if (Boolean.TRUE.equals(item.getDiscarded())) {
                continue;
            }
            ItemType type;
            try {
                type = ItemType.fromWire(item.getType());
            } catch (IllegalArgumentException e) {
                continue;
            }
            switch (type) {
                case MESSAGE -> {
                    flushToolCalls(out, pendingToolCalls);
                    pendingToolCalls.clear();
                    pendingStep = null;
                    Map<String, Object> content = readMap(item.getContentJson());
                    Object text = content.getOrDefault("text", "");
                    out.add(Map.of("role", item.getRole() == null ? "user" : item.getRole(),
                            "content", String.valueOf(text)));
                }
                case TOOL_CALL -> {
                    if (pendingStep != null && !pendingStep.equals(item.getStep())) {
                        flushToolCalls(out, pendingToolCalls);
                        pendingToolCalls.clear();
                    }
                    pendingStep = item.getStep();
                    pendingToolCalls.add(item);
                }
                case TOOL_RESULT -> {
                    Map<String, Object> content = readMap(item.getContentJson());
                    // RT-09：pending 占位不进模型上下文，等 resolves 结果
                    if (content.containsKey("pending")) {
                        continue;
                    }
                    flushToolCalls(out, pendingToolCalls);
                    pendingToolCalls.clear();
                    pendingStep = null;
                    out.add(Map.of(
                            "role", "tool",
                            "tool_call_id", String.valueOf(content.getOrDefault("callId", "")),
                            "content", String.valueOf(content.getOrDefault("output", ""))));
                }
                default -> {
                    // reasoning / status / error 不进上下文
                }
            }
        }
        flushToolCalls(out, pendingToolCalls);
        return out;
    }

    private void flushToolCalls(List<Map<String, Object>> out, List<RuntimeItemDO> pending) {
        if (pending.isEmpty()) {
            return;
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        for (RuntimeItemDO item : pending) {
            Map<String, Object> content = readMap(item.getContentJson());
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", content.getOrDefault("name", ""));
            Object args = content.get("arguments");
            fn.put("arguments", args instanceof String s ? s : writeJson(args == null ? Map.of() : args));
            calls.add(Map.of(
                    "id", String.valueOf(content.getOrDefault("callId", "")),
                    "type", "function",
                    "function", fn));
        }
        out.add(Map.of("role", "assistant", "tool_calls", calls));
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
