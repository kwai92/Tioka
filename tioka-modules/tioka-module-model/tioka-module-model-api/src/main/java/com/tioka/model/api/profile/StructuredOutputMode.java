package com.tioka.model.api.profile;

/**
 * 结构化生成方式（ADR-066 §4）。SCH-02 覆盖前三档常用形态；PROMPT 留给降级阶梯。
 */
public enum StructuredOutputMode {
    /** 原生严格 JSON Schema（response_format=json_schema）。 */
    STRICT_SCHEMA,
    /** 严格工具调用（emit_output + strict）。 */
    STRICT_TOOL,
    /** JSON Object 模式 + 提示中的方言 Schema。 */
    JSON_OBJECT,
    /** 非严格工具调用（emit_output）。 */
    TOOL,
    /** 仅靠提示约束。 */
    PROMPT;

    /** ADR-037 三档别名 → 能力方式。 */
    public static StructuredOutputMode fromLegacy(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return null;
        }
        return switch (legacy.trim().toUpperCase()) {
            case "SCHEMA" -> STRICT_SCHEMA;
            case "JSON" -> JSON_OBJECT;
            case "TOOL" -> TOOL;
            default -> StructuredOutputMode.valueOf(legacy.trim().toUpperCase());
        };
    }
}
