package com.tioka.runtime.api.spi;

/** Cedar / 授权书策略结果（ADR-033）。 */
public enum PolicyOutcome {
    ALLOW,
    DENY,
    ESCALATE
}
