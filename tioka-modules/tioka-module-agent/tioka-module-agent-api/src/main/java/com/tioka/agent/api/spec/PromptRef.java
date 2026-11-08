package com.tioka.agent.api.spec;

/**
 * Prompt 模板引用（AST-08）。可空；null 表示沿用现状（内联 instructions）。
 */
public record PromptRef(
        String templateId,
        Integer pinnedVersion
) {}
