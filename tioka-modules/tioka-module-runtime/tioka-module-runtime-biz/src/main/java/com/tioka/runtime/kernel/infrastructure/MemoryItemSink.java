package com.tioka.runtime.kernel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Item 落库并发布 Redis 事件流（RT-04）。 */
@Component
public class MemoryItemSink implements LoopPorts.ItemSink {
    private final ItemRepository items;
    private final ObjectMapper mapper;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ConcurrentHashMap<String, StreamState> streaming = new ConcurrentHashMap<>();

    public MemoryItemSink(ItemRepository items,
                          ObjectMapper mapper,
                          EventStream streams,
                          RuntimeEventFactory events) {
        this.items = items;
        this.mapper = mapper;
        this.streams = streams;
        this.events = events;
    }

    @Override
    public void delta(LoopContext ctx, LoopPorts.ModelPort.Delta delta) {
        StreamState st = streaming.computeIfAbsent(ctx.turnId(), k -> {
            long seq = items.reserveSeq(ctx.threadId(), ctx.turnId(), ctx.leaseVersion());
            String itemId = Ids.newId("itm");
            String entry = streams.publish(ctx.threadId(), EventId.of(seq, 0).toString(),
                    RuntimeEventType.ITEM_CREATED.wire(),
                    events.itemCreated(ctx.threadId(), ctx.turnId(), itemId, seq, ItemType.MESSAGE.wire()));
            if (entry != null) {
                streams.setCurrentEntry(ctx.threadId(), entry);
            }
            return new StreamState(seq, itemId);
        });
        int index = st.nextDelta();
        streams.publish(ctx.threadId(), EventId.of(st.seq, index).toString(),
                RuntimeEventType.ITEM_DELTA.wire(),
                events.itemDelta(ctx.threadId(), ctx.turnId(), st.itemId, index, delta.text(), delta.reasoning()));
    }

    @Override
    public void completeMessage(LoopContext ctx, int step, String text) {
        StreamState st = streaming.remove(ctx.turnId());
        RuntimeItemDO item;
        if (st != null) {
            items.insertReserved(st.seq, ctx.threadId(), ctx.turnId(), step,
                    ItemType.MESSAGE.wire(), "assistant", json(Map.of("text", text)), null);
            item = new RuntimeItemDO();
            item.setId(st.itemId);
            item.setSessionId(ctx.threadId());
            item.setTurnId(ctx.turnId());
            item.setSeq(st.seq);
            item.setStep(step);
            item.setType(ItemType.MESSAGE.wire());
            item.setRole("assistant");
            item.setContentJson(json(Map.of("text", text)));
            streams.clearCurrentEntry(ctx.threadId());
        } else {
            item = items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                    ItemType.MESSAGE.wire(), "assistant", json(Map.of("text", text)), null);
        }
        streams.publish(ctx.threadId(), EventId.of(item.getSeq()).toString(),
                RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(ctx.threadId(), item));
    }

    @Override
    public void complete(LoopContext ctx, int step, String type, String role, String contentJson) {
        RuntimeItemDO item = items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step, type, role, contentJson, null);
        streams.publish(ctx.threadId(), EventId.of(item.getSeq()).toString(),
                RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(ctx.threadId(), item));
    }

    @Override
    public void toolCall(LoopContext ctx, int step, LoopPorts.ToolCall call) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", call.callId());
        content.put("name", call.name());
        content.put("arguments", parseArgs(call.argumentsJson()));
        RuntimeItemDO item = items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                ItemType.TOOL_CALL.wire(), "assistant", json(content), null);
        streams.publish(ctx.threadId(), EventId.of(item.getSeq()).toString(),
                RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(ctx.threadId(), item));
    }

    @Override
    public void toolResult(LoopContext ctx, int step, LoopPorts.ToolOutcome outcome) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", outcome.callId());
        content.put("name", outcome.name());
        content.put("ok", outcome.ok());
        content.put("output", outcome.output());
        content.put("durationMs", outcome.durationMs());
        RuntimeItemDO item = items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                ItemType.TOOL_RESULT.wire(), "tool", json(content), null);
        streams.publish(ctx.threadId(), EventId.of(item.getSeq()).toString(),
                RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(ctx.threadId(), item));
    }

    @Override
    public void status(LoopContext ctx, int step, String kind) {
        RuntimeItemDO item = items.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                ItemType.STATUS.wire(), "system", json(Map.of("kind", kind)), null);
        streams.publish(ctx.threadId(), EventId.of(item.getSeq()).toString(),
                RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(ctx.threadId(), item));
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

    private static final class StreamState {
        final long seq;
        final String itemId;
        private final java.util.concurrent.atomic.AtomicInteger deltaIndex =
                new java.util.concurrent.atomic.AtomicInteger();

        StreamState(long seq, String itemId) {
            this.seq = seq;
            this.itemId = itemId;
        }

        int nextDelta() {
            return deltaIndex.incrementAndGet();
        }
    }
}
