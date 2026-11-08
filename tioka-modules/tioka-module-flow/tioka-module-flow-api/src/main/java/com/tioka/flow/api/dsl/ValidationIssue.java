package com.tioka.flow.api.dsl;

/**
 * 发布校验一条问题（ADR-043 §3.7）。
 */
public record ValidationIssue(
        String code,
        ValidationSeverity severity,
        String message,
        String path
) {
}
