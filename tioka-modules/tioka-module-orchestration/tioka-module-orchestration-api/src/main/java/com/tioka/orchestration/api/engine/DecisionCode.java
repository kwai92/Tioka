package com.tioka.orchestration.api.engine;

/**
 * 规则决定码（ADR-020；表 {@code orch_decision.decision}）。
 */
public enum DecisionCode {
    ACCEPT,
    IGNORE,
    STOP,
    UNAVAILABLE,
    WOULD_ACCEPT
}
