package com.tioka.governance.api.charter;

/** 授权书生命周期状态（ADR-032；与 Agent 版本无关，记在 Agent 上）。 */
public enum CharterStatus {
    ACTIVE,
    SUSPENDED,
    HANDOVER
}
