package com.tioka.governance.api.autonomy;

/**
 * 自治等级闸门（ADR-034 / TRU-09，管线第 10 步）。
 */
public interface AutonomyGate {

    AutonomyDecision decide(AutonomyContext context);
}
