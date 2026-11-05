package com.tioka.runtime.kernel.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 多步工具循环（纯编排；依赖端口与仓储）。
 */
public final class AgentLoop {
    private final LoopPorts.ModelPort modelPort;
    private final LoopPorts.ToolPort toolPort;
    private final LoopPorts.ItemSink sink;
    private final ContextBuilder contextBuilder;
    private final TurnRepository turns;
    private final Function<String, List<RuntimeItemDO>> itemLoader;
    private final ObjectMapper mapper;

    public AgentLoop(LoopPorts.ModelPort modelPort,
                     LoopPorts.ToolPort toolPort,
                     LoopPorts.ItemSink sink,
                     ContextBuilder contextBuilder,
                     TurnRepository turns,
                     Function<String, List<RuntimeItemDO>> itemLoader,
                     ObjectMapper mapper) {
        this.modelPort = modelPort;
        this.toolPort = toolPort;
        this.sink = sink;
        this.contextBuilder = contextBuilder;
        this.turns = turns;
        this.itemLoader = itemLoader;
        this.mapper = mapper;
    }

    public LoopResult run(LoopContext ctx) {
        List<Map<String, Object>> tools = toolPort.describe(ctx);
        Deque<String> recentCalls = new ArrayDeque<>();
        int promptTokens = 0;
        int completionTokens = 0;
        try {
            for (int step = ctx.startStep(); ; step++) {
                ctx.checkpoint();
                boolean last = step >= ctx.maxSteps() || ctx.isForceFinalAnswer();
                List<Map<String, Object>> messages = contextBuilder.build(itemLoader.apply(ctx.threadId()));
                LoopPorts.ModelPort.Result r = modelPort.chat(ctx, messages, last ? List.of() : tools,
                        delta -> sink.delta(ctx, delta));
                promptTokens += r.promptTokens();
                completionTokens += r.completionTokens();
                if (r.reasoning() != null && !r.reasoning().isBlank()) {
                    sink.complete(ctx, step, ItemType.REASONING.wire(), "assistant",
                            json(Map.of("text", r.reasoning())));
                }
                if (r.text() != null && !r.text().isBlank()) {
                    sink.completeMessage(ctx, step, r.text());
                }
                turns.incrementStep(ctx.turnId(), ctx.leaseVersion());
                if (r.toolCalls().isEmpty()) {
                    return LoopResult.completed(promptTokens, completionTokens);
                }
                if (last) {
                    sink.status(ctx, step, "max_steps_reached");
                    return LoopResult.completed(promptTokens, completionTokens);
                }
                if (wouldRepeat(recentCalls, r.toolCalls(), ctx.repeatLimit())) {
                    sink.status(ctx, step, "repeat_call_stopped");
                    ctx.forceFinalAnswer();
                    continue;
                }
                for (LoopPorts.ToolCall c : r.toolCalls()) {
                    sink.toolCall(ctx, step, c);
                    recentCalls.addLast(keyOf(c));
                    while (recentCalls.size() > 32) {
                        recentCalls.removeFirst();
                    }
                }
                List<LoopPorts.ToolOutcome> outs = toolPort.invokeAll(ctx, r.toolCalls());
                for (LoopPorts.ToolOutcome o : outs) {
                    sink.toolResult(ctx, step, o);
                }
            }
        } catch (CancelledSignal e) {
            sink.status(ctx, 0, "cancelled");
            return LoopResult.cancelled(promptTokens, completionTokens);
        }
    }

    /** 若本步任一调用会成为「最近 limit 次全相同」中的第 limit 次，则触发。 */
    static boolean wouldRepeat(Deque<String> recent, List<LoopPorts.ToolCall> calls, int limit) {
        if (limit <= 0 || calls.isEmpty()) {
            return false;
        }
        List<String> hist = new ArrayList<>(recent);
        for (LoopPorts.ToolCall c : calls) {
            String k = keyOfStatic(c);
            int trailing = 0;
            for (int i = hist.size() - 1; i >= 0; i--) {
                if (k.equals(hist.get(i))) {
                    trailing++;
                } else {
                    break;
                }
            }
            if (trailing + 1 >= limit) {
                return true;
            }
        }
        return false;
    }

    private String keyOf(LoopPorts.ToolCall c) {
        return keyOfStatic(c);
    }

    private static String keyOfStatic(LoopPorts.ToolCall c) {
        String args = c.argumentsJson() == null ? "" : c.argumentsJson().trim();
        return c.name() + "|" + args;
    }

    private String json(Map<String, ?> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
