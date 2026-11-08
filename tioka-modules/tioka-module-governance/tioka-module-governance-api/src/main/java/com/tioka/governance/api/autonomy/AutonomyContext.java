package com.tioka.governance.api.autonomy;

import com.tioka.governance.api.policy.PolicyDecision;

/**
 * {@link AutonomyGate#decide} 输入（ADR-034 判定表）。
 */
public record AutonomyContext(
        PolicyDecision cedar,
        String origin,
        int level,
        boolean reversible,
        boolean estimated,
        String risk,
        boolean inObservationPeriod,
        String toolPolicy
) {}
