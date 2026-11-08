package com.tioka.runtime.agui.dto;

import java.util.List;
import java.util.Map;

/**
 * AG-UI {@code RunAgentInput}（字段对齐 {@code @ag-ui/core}，Jackson camelCase）。
 */
public record AgUiRunAgentInput(
        String threadId,
        String runId,
        List<AgUiMessage> messages,
        List<Map<String, Object>> tools,
        Map<String, Object> state,
        List<Map<String, Object>> context,
        Map<String, Object> forwardedProps
) {
    public record AgUiMessage(String id, String role, String content) {}
}
