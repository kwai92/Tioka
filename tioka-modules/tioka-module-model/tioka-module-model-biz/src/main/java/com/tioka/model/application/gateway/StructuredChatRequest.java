package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.model.api.profile.StructuredOutputMode;

import java.util.List;
import java.util.Map;

/**
 * 结构化约束请求形态（SCH-02）。尚未接入 {@code DefaultModelGateway}（SCH-03）。
 */
public record StructuredChatRequest(
        StructuredOutputMode selectedMode,
        Map<String, Object> responseFormat,
        String systemPromptSuffix,
        List<Map<String, Object>> tools,
        JsonNode dialectSchema,
        String dialectHash
) {
}
