package com.tioka.governance.api.policy;

/**
 * 命中的策略条目（M3-05）。
 */
public record PolicyHit(String policyId, PolicyMode mode, PolicyDecision effect) {}
