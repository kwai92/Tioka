package com.tioka.model.api.constraint;

import java.util.Map;

/**
 * TOOL / STRICT_TOOL 方式的约束参数（{@code emit_output} 工具参数 Schema）。
 */
public record EmitOutputToolConstraintParams(
        String toolName,
        boolean strict,
        Map<String, Object> parameters
) {
    public EmitOutputToolConstraintParams {
        if (toolName == null || toolName.isBlank()) {
            toolName = "emit_output";
        }
        if (parameters == null) {
            parameters = Map.of();
        } else {
            parameters = Map.copyOf(parameters);
        }
    }
}
