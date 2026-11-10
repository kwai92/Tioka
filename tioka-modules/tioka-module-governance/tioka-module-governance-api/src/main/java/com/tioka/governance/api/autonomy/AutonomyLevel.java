package com.tioka.governance.api.autonomy;

/**
 * 自治等级（ADR-034）。等级判定表见 {@link AutonomyGate}。
 */
public enum AutonomyLevel {
    L0, L1, L2, L3, L4;

    /** {@code "L2"} → {@link AutonomyLevel}；非法输入抛 IllegalArgumentException。 */
    public static AutonomyLevel fromCode(String code) {
        return AutonomyLevel.valueOf(code.trim().toUpperCase());
    }

    public int ordinalLevel() {
        return ordinal();
    }
}
