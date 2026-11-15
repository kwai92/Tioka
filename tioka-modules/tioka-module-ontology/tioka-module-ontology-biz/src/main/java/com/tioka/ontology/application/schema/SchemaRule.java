package com.tioka.ontology.application.schema;

/**
 * {@code x-tioka-rules} 中的一条语义规则（ADR-066 §5.1）。
 *
 * @param id      规则 ID（同期 Schema 内唯一）
 * @param expr    CEL 表达式，变量 {@code self}（当前对象）与 {@code root}（根 payload）；结果为布尔
 * @param message 给模型与人看的说明
 * @param severity {@code ERROR} 阻断并进入修复；{@code WARN} 不阻断，结局记 {@code VALID_WITH_UNKNOWN}
 * @param hint    是否把该规则写进生成方言的说明（ADR-066 §2）
 * @param pointer 规则所在 JSON Pointer（诊断用）
 */
public record SchemaRule(
        String id,
        String expr,
        String message,
        Severity severity,
        boolean hint,
        String pointer
) {
    public enum Severity {
        ERROR,
        WARN
    }
}
