package com.tioka.runtime.kernel.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.kernel.approval.PendingToolCall;
import com.tioka.runtime.kernel.approval.ToolApprovalCoordinator;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 多步工具循环（纯编排；依赖端口与仓储）。RT-07：策略审批挂起与续跑。
 */
public final class AgentLoop {
    private final LoopPorts.ModelPort modelPort;
    private final LoopPorts.ToolPort toolPort;
    private final LoopPorts.ItemSink sink;
    private final ContextBuilder contextBuilder;
    private final TurnRepository turns;
    private final Function<String, List<RuntimeItemDO>> itemLoader;
    private final ObjectMapper mapper;
    private final ToolApprovalCoordinator approvals;

    public AgentLoop(LoopPorts.ModelPort modelPort,
                     LoopPorts.ToolPort toolPort,
                     LoopPorts.ItemSink sink,
                     ContextBuilder contextBuilder,
                     TurnRepository turns,
                     Function<String, List<RuntimeItemDO>> itemLoader,
                     ObjectMapper mapper) {
        this(modelPort, toolPort, sink, contextBuilder, turns, itemLoader, mapper, null);
    }

    public AgentLoop(LoopPorts.ModelPort modelPort,
                     LoopPorts.ToolPort toolPort,
                     LoopPorts.ItemSink sink,
                     ContextBuilder contextBuilder,
                     TurnRepository turns,
                     Function<String, List<RuntimeItemDO>> itemLoader,
                     ObjectMapper mapper,
                     ToolApprovalCoordinator approvals) {
        this.modelPort = modelPort;
        this.toolPort = toolPort;
        this.sink = sink;
        this.contextBuilder = contextBuilder;
        this.turns = turns;
        this.itemLoader = itemLoader;
        this.mapper = mapper;
        this.approvals = approvals;
    }

    public LoopResult run(LoopContext ctx) {
        List<Map<String, Object>> tools = toolPort.describe(ctx);
        Deque<String> recentCalls = new ArrayDeque<>();
        int promptTokens = 0;
        int completionTokens = 0;
        try {
            if (approvals != null) {
                approvals.resumeOrphans(ctx, toolPort, sink);
            }
            for (int step = ctx.startStep(); ; step++) {
                ctx.checkpoint();
                applySteers(ctx, step);
                boolean last = step >= ctx.maxSteps() || ctx.isForceFinalAnswer();
                List<Map<String, Object>> messages = contextBuilder.build(itemLoader.apply(ctx.threadId()));
                LoopPorts.ModelPort.Result r = modelPort.chat(ctx, messages, last ? List.of() : tools,
                        delta -> {
                            ctx.checkpoint();
                            sink.delta(ctx, delta);
                        });
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
                List<ToolApprovalCoordinator.CallBinding> bindings = new ArrayList<>();
                for (LoopPorts.ToolCall c : r.toolCalls()) {
                    String itemId = sink.toolCall(ctx, step, c);
                    bindings.add(new ToolApprovalCoordinator.CallBinding(itemId, c));
                    recentCalls.addLast(keyOf(c));
                    while (recentCalls.size() > 32) {
                        recentCalls.removeFirst();
                    }
                }
                if (approvals == null) {
                    List<LoopPorts.ToolOutcome> outs = toolPort.invokeAll(ctx, r.toolCalls());
                    for (LoopPorts.ToolOutcome o : outs) {
                        sink.toolResult(ctx, step, o);
                    }
                    continue;
                }
                List<PendingToolCall> classified = approvals.classify(ctx, bindings);
                List<LoopPorts.ToolCall> allow = new ArrayList<>();
                List<PendingToolCall> require = new ArrayList<>();
                for (PendingToolCall p : classified) {
                    if (p.decision() instanceof ApprovalPolicy.Allow) {
                        allow.add(p.call());
                    } else if (p.decision() instanceof ApprovalPolicy.Deny d) {
                        sink.toolResult(ctx, step, new LoopPorts.ToolOutcome(
                                p.call().callId(), p.call().name(), false,
                                "策略拒绝：" + d.reason(), 0));
                    } else if (p.decision() instanceof ApprovalPolicy.RequireApproval) {
                        require.add(p);
                    }
                }
                if (!allow.isEmpty()) {
                    List<LoopPorts.ToolOutcome> outs = toolPort.invokeAll(ctx, allow);
                    for (LoopPorts.ToolOutcome o : outs) {
                        sink.toolResult(ctx, step, o);
                    }
                }
                if (!require.isEmpty()) {
                    approvals.suspend(ctx, require);
                    return LoopResult.waitingApproval(promptTokens, completionTokens);
                }
            }
        } catch (CancelledSignal e) {
            sink.status(ctx, 0, "cancelled");
            return LoopResult.cancelled(e.reason(), promptTokens, completionTokens);
        }
    }

    private void applySteers(LoopContext ctx, int step) {
        List<String> steers = ctx.pollSteers();
        for (String text : steers) {
            if (text == null || text.isBlank()) {
                continue;
            }
            sink.complete(ctx, step, ItemType.MESSAGE.wire(), "user",
                    json(Map.of("text", text, "steer", true)));
            sink.status(ctx, step, "steer_applied");
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
