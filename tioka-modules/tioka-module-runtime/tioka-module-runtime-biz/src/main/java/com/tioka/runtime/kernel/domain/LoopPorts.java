package com.tioka.runtime.kernel.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class LoopPorts {
    private LoopPorts() {}

    public interface ModelPort {
        Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                    Consumer<Delta> onDelta);

        /**
         * SCH-02：携带结构化输出需求的调用（ADR-037 / ADR-066）；缺省降级为无约束调用，
         * 便于既有桩实现无须感知。
         */
        default Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                            Consumer<Delta> onDelta, com.tioka.model.api.constraint.StructuredDemand structured) {
            return chat(ctx, messages, tools, onDelta);
        }

        /** {@code discarded=true} 表示本步已流出增量作废（MH-02）。 */
        record Delta(String text, String reasoning, boolean discarded) {
            public Delta(String text, String reasoning) {
                this(text, reasoning, false);
            }

            public static Delta discard() {
                return new Delta(null, null, true);
            }
        }

        /**
         * @param finishReason 供应商结束原因（{@code stop} / {@code length} / {@code content_filter} …）；
         *                     第 1 步完成状态识别用（ADR-066 §5 / SCH-07），未知为 {@code null}
         * @param refused      供应商拒答信号（ADR-066 §5 第 1 步）；拒答不按格式错误修复
         */
        record Result(String text, String reasoning, List<ToolCall> toolCalls,
                      int promptTokens, int completionTokens, String modelRef, String finishReason,
                      boolean refused) {
            public Result {
                toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            }

            /** 兼容 7 参口径（未携带拒答信号）。 */
            public Result(String text, String reasoning, List<ToolCall> toolCalls,
                          int promptTokens, int completionTokens, String modelRef, String finishReason) {
                this(text, reasoning, toolCalls, promptTokens, completionTokens, modelRef, finishReason, false);
            }

            /** 兼容 6 参口径（finishReason 未知）。 */
            public Result(String text, String reasoning, List<ToolCall> toolCalls,
                          int promptTokens, int completionTokens, String modelRef) {
                this(text, reasoning, toolCalls, promptTokens, completionTokens, modelRef, null, false);
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

    /** SCH-09 §7.4：人工补录任务端口（生成 {@code data.complete} 任务）。 */
    public interface HumanTaskPort {
        /**
         * 生成人工补录任务。
         *
         * @param schemaRef        权威 Schema 引用
         * @param candidateJson    预填候选（{@code lastCandidate}，可为 {@code null}）
         * @param pendingPointers  待补全字段的 JSON Pointer 清单
         * @return 任务 ID；{@code null} 表示未接线/无法创建（OutputStage 降级为 FAILED）
         */
        String create(LoopContext ctx, int step, String schemaRef, String candidateJson,
                      List<String> pendingPointers);

        /**
         * SCH-09 §7.4：取出该 Turn 已提交待续跑的补录任务（原 Turn 从 WAITING 续跑时消费）。
         *
         * @return {@code null} = 无已提交任务（走常规循环）
         */
        default SubmittedCompletion takeSubmitted(LoopContext ctx) {
            return null;
        }

        /** 已提交的人工补录结果（§7.4）：{@code answerJson} 经第 3〜8 步校验后写 data item。 */
        record SubmittedCompletion(String taskId, String schemaRef, String answerJson, String submittedBy) {}
    }

    public record ToolCall(String callId, String name, String argumentsJson) {}

    public record ToolOutcome(String callId, String name, boolean ok, String output, long durationMs,
                              String approvedBy, com.tioka.runtime.api.spi.ToolProvider.PendingJob pending,
                              String dataLabel) {
        public ToolOutcome(String callId, String name, boolean ok, String output, long durationMs) {
            this(callId, name, ok, output, durationMs, null, null, null);
        }

        public ToolOutcome(String callId, String name, boolean ok, String output, long durationMs,
                           String approvedBy) {
            this(callId, name, ok, output, durationMs, approvedBy, null, null);
        }

        public ToolOutcome(String callId, String name, boolean ok, String output, long durationMs,
                           String approvedBy, com.tioka.runtime.api.spi.ToolProvider.PendingJob pending) {
            this(callId, name, ok, output, durationMs, approvedBy, pending, null);
        }
    }
}
