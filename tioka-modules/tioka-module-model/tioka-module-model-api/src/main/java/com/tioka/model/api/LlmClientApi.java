package com.tioka.model.api;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmClientApi {
    CompletionResult complete(String baseUrl, String apiKeyRef, String model,
                              List<Map<String, Object>> messages,
                              List<Map<String, Object>> tools) throws Exception;

    /**
     * 真流式补全：增量通过 {@code onDelta} 回调；方法返回时汇总完整 {@link CompletionResult}。
     * Runtime 不得再对返回内容做 substring 切块冒充流式。
     */
    CompletionResult streamComplete(String baseUrl, String apiKeyRef, String model,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    Consumer<StreamDelta> onDelta) throws Exception;

    record ToolCall(String id, String name, String argumentsJson) {}

    record CompletionResult(String content, List<ToolCall> toolCalls, int promptTokens, int completionTokens) {}

    /**
     * @param textDelta       文本增量（可空）
     * @param toolCalls       本片工具调用（可空；通常仅终态汇总到 CompletionResult）
     * @param promptTokens    usage 增量/终态（可空）
     * @param completionTokens usage 增量/终态（可空）
     */
    record StreamDelta(String textDelta, List<ToolCall> toolCalls, Integer promptTokens, Integer completionTokens) {
        public static StreamDelta text(String delta) {
            return new StreamDelta(delta, null, null, null);
        }
    }
}
