package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.model.api.constraint.StructuredConstraintRequest;
import com.tioka.model.api.profile.StructuredOutputMode;

import java.util.List;
import java.util.Map;

/**
 * @deprecated 使用 {@link StructuredConstraintRequest}（model-api）。保留别名以免破坏早期 SCH-02 引用。
 */
@Deprecated(forRemoval = false)
public record StructuredChatRequest(
        StructuredOutputMode selectedMode,
        Map<String, Object> responseFormat,
        String systemPromptSuffix,
        List<Map<String, Object>> tools,
        JsonNode dialectSchema,
        String dialectHash
) {
    public StructuredConstraintRequest toApi() {
        return new StructuredConstraintRequest(
                selectedMode, responseFormat, systemPromptSuffix, tools, dialectSchema, dialectHash);
    }

    public static StructuredChatRequest fromApi(StructuredConstraintRequest req) {
        return new StructuredChatRequest(
                req.selectedMode(),
                req.responseFormat(),
                req.systemPromptSuffix(),
                req.tools(),
                req.dialectSchema(),
                req.dialectHash());
    }
}
