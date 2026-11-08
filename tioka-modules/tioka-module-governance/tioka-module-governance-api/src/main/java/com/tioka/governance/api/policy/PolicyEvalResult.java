package com.tioka.governance.api.policy;

import java.util.List;

/**
 * 策略求值输出（M3-05）。
 */
public record PolicyEvalResult(
        PolicyDecision decision,
        List<PolicyHit> hits,
        String policySetVersion,
        boolean evalError
) {}
