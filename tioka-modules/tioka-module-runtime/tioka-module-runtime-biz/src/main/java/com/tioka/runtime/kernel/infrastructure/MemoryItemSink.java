package com.tioka.runtime.kernel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** M0：item 落库；delta 仅在后续 RT-04 进 Redis。 */
@Component
public class MemoryItemSink implements LoopPorts.ItemSink {
    private final ItemRepository items;
    private final ObjectMapper mapper;

    public MemoryItemSink(ItemRepository items, ObjectMapper mapper) {
        this.items = items;
        this.mapper = mapper;
    }

    @Override
    public void delta(LoopContext ctx, LoopPorts.ModelPort.Delta delta) {
        // RT-04：只写 Redis 事件流
    }

    @Override
    public void completeMessage(LoopContext ctx, int step, String text) {
        items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                ItemType.MESSAGE.wire(), "assistant", json(Map.of("text", text)), null);
    }

    @Override
    public void complete(LoopContext ctx, int step, String type, String role, String contentJson) {
        items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step, type, role, contentJson, null);
    }

    @Override
    public void toolCall(LoopContext ctx, int step, LoopPorts.ToolCall call) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", call.callId());
        content.put("name", call.name());
        content.put("arguments", parseArgs(call.argumentsJson()));
        items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                ItemType.TOOL_CALL.wire(), "assistant", json(content), null);
    }

    @Override
    public void toolResult(LoopContext ctx, int step, LoopPorts.ToolOutcome outcome) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", outcome.callId());
        content.put("name", outcome.name());
        content.put("ok", outcome.ok());
        content.put("output", outcome.output());
        content.put("durationMs", outcome.durationMs());
        items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                ItemType.TOOL_RESULT.wire(), "tool", json(content), null);
    }

    @Override
    public void status(LoopContext ctx, int step, String kind) {
        items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                ItemType.STATUS.wire(), "system", json(Map.of("kind", kind)), null);
    }

    private Object parseArgs(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
