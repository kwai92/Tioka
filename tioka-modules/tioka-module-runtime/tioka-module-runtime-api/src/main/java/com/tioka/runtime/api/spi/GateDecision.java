package com.tioka.runtime.api.spi;

/** 自治 / 审批闸门判定（ADR-033 / ADR-034）。 */
public enum GateDecision {
    AUTO_COMMIT,
    REQUIRE_APPROVAL,
    SHADOW_ONLY,
    DENIED
}
