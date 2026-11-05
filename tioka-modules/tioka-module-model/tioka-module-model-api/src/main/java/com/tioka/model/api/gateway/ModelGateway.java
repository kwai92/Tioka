package com.tioka.model.api.gateway;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 全平台三个治理卡点之一（ADR-017）。一次调用内部依次：选模（冻结）→ 配额预检 → 限流 →
 * 调用（重试 2 次，故障接力最多 2 次）→ 记账。
 *
 * <p>配额不足抛 {@code QUOTA_EXCEEDED}；接力耗尽抛 {@code DEPENDENCY}。
 * 首个 delta 发出后发生接力时，{@code onDelta} 会收到 {@link StreamDelta#discard()}，
 * 调用方须作废本步已流出的内容（ADR-029 第 2 条）。
 */
public interface ModelGateway {

    ChatResult chat(ModelCallContext ctx, ChatRequest request, Consumer<StreamDelta> onDelta);

    EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request);

    RerankResult rerank(ModelCallContext ctx, RerankRequest request);

    // ---- chat ----

    /**
     * @param messages    OpenAI 兼容消息
     * @param tools       OpenAI 兼容工具定义
     * @param temperature 可空
     * @param maxTokens   可空
     */
    record ChatRequest(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                       Double temperature, Integer maxTokens) {}

    record ToolCall(String id, String name, String argumentsJson) {}

    /**
     * @param bindingId     实际使用的模型绑定
     * @param failoverCount 本次调用发生的接力次数
     */
    record ChatResult(String content, String reasoning, List<ToolCall> toolCalls,
                      int promptTokens, int completionTokens,
                      String bindingId, String modelRef, int failoverCount, String finishReason) {}

    /** 流式增量。{@code discarded=true} 表示此前增量作废。 */
    record StreamDelta(String textDelta, String reasoningDelta, boolean discarded) {
        public static StreamDelta text(String delta) {
            return new StreamDelta(delta, null, false);
        }

        public static StreamDelta discard() {
            return new StreamDelta(null, null, true);
        }
    }

    // ---- embedding ----

    /** @param bindingId 知识库绑定的嵌入模型；null 用空间默认 */
    record EmbeddingRequest(String bindingId, List<String> inputs) {}

    record EmbeddingResult(List<float[]> vectors, int dimension, int promptTokens, String bindingId) {}

    // ---- rerank ----

    record RerankRequest(String bindingId, String query, List<String> documents, int topN) {}

    record RerankResult(List<Ranked> results, String bindingId) {
        public record Ranked(int index, double score) {}
    }
}
