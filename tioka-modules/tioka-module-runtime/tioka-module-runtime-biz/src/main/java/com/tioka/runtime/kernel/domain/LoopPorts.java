package com.tioka.runtime.kernel.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class LoopPorts {
    private LoopPorts() {}

    public interface ModelPort {
        Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                    Consumer<Delta> onDelta);

        /** {@code discarded=true} 表示本步已流出增量作废（MH-02）。 */
        record Delta(String text, String reasoning, boolean discarded) {
            public Delta(String text, String reasoning) {
                this(text, reasoning, false);
            }

            public static Delta discard() {
                return new Delta(null, null, true);
            }
        }

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

        /**
         * MH-02：作废本 Turn 当前流式 item（发 {@code item.discarded}，清预占 seq）。
         * 默认空实现，便于单测桩。
         */
        default void discardInFlight(LoopContext ctx) {}

        void completeMessage(LoopContext ctx, int step, String text);

        void complete(LoopContext ctx, int step, String type, String role, String contentJson);

        /** @return 写入的 tool_call itemId（审批挂起需关联） */
        String toolCall(LoopContext ctx, int step, ToolCall call);

        void toolResult(LoopContext ctx, int step, ToolOutcome outcome);

        void status(LoopContext ctx, int step, String kind);

        /** 取消时把流式中的文本以 interrupted:true 落库；无流式则无操作。 */
        default void flushInterrupted(LoopContext ctx, int step) {}
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

    public record ToolOutcome(String callId, String name, boolean ok, String output, long durationMs,
                              String approvedBy, com.tioka.runtime.api.spi.ToolProvider.PendingJob pending) {
        public ToolOutcome(String callId, String name, boolean ok, String output, long durationMs) {
            this(callId, name, ok, output, durationMs, null, null);
        }

        public ToolOutcome(String callId, String name, boolean ok, String output, long durationMs,
                           String approvedBy) {
            this(callId, name, ok, output, durationMs, approvedBy, null);
        }
    }
}
