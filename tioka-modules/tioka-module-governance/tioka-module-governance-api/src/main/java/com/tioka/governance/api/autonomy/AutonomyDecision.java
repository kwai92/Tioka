package com.tioka.governance.api.autonomy;

/** 自治等级判定结果（ADR-034 / TRU-09）。 */
public enum AutonomyDecision {
    AUTO_COMMIT,
    REQUIRE_APPROVAL,
    SHADOW_ONLY,
    DENIED
}
