package com.tioka.model.api.constraint;

/**
 * JSON / JSON_OBJECT 方式的约束参数（{@code response_format=json_object} + 提示中的方言 Schema）。
 */
public record JsonObjectConstraintParams(
        String systemPromptSuffix,
        boolean requireJsonKeyword
) {
    public JsonObjectConstraintParams {
        if (systemPromptSuffix == null) {
            systemPromptSuffix = "";
        }
    }
}
