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
 */
public final class ContextBuilder {
    private final ObjectMapper mapper;

    public ContextBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> build(List<RuntimeItemDO> items) {
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
