package com.tioka.ontology.application.pack;

/**
 * 校验违规。{@link Severity#REJECT} 回抽；{@link Severity#REVIEW} 标待复核。
 */
public record Violation(String validatorId, String path, String message, Severity severity) {

    public enum Severity {
        REJECT,
        REVIEW
    }
}
