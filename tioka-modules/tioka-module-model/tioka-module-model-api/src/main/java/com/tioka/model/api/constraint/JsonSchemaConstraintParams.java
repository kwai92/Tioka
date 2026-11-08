package com.tioka.model.api.constraint;

import java.util.Map;

/**
 * SCHEMA / STRICT_SCHEMA 方式的约束参数（{@code response_format=json_schema}）。
 */
public record JsonSchemaConstraintParams(
        String name,
        boolean strict,
        Map<String, Object> schema
) {
    public JsonSchemaConstraintParams {
        if (name == null || name.isBlank()) {
            name = "tioka_output";
        }
        if (schema == null) {
            schema = Map.of();
        } else {
            schema = Map.copyOf(schema);
        }
    }
}
