package com.tioka.runtime.kernel.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class LoopPorts {
    private LoopPorts() {}

    public interface ModelPort {
        Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                    Consumer<Delta> onDelta);

        record Delta(String text, String reasoning) {}

        record Result(String text, String reasoning, List<ToolCall> toolCalls,
                      int promptTokens, int completionTokens, String modelRef) {
            public Result {
                toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            }
        }
    }

    public interface ToolPort {
        List<Map<String, Object>> describe(LoopContext ctx);

        List<ToolOutcome> invokeAll(LoopContext ctx, List<ToolCall> calls);
    }

    public interface ItemSink {
        void delta(LoopContext ctx, ModelPort.Delta delta);

        void completeMessage(LoopContext ctx, int step, String text);

        void complete(LoopContext ctx, int step, String type, String role, String contentJson);

        void toolCall(LoopContext ctx, int step, ToolCall call);

        void toolResult(LoopContext ctx, int step, ToolOutcome outcome);

        void status(LoopContext ctx, int step, String kind);
    }

    public interface SignalPort {
        /** 取消时抛 CancelledSignal；租约丢失抛 LeaseLost。 */
        void checkpoint(LoopContext ctx);

        /** 取出并清空本 Turn 待注入的插话文本。 */
        default List<String> pollSteers(LoopContext ctx) {
            return List.of();
        }
    }

    public record ToolCall(String callId, String name, String argumentsJson) {}

    public record ToolOutcome(String callId, String name, boolean ok, String output, long durationMs) {}
}
