package com.tioka.orchestration.api.engine;

/**
 * 工单并发模式（表 {@code orch_rule_version.concurrency}，wire 小写）。
 */
public enum ConcurrencyMode {
    SERIAL("serial"),
    MERGE("merge"),
    PARALLEL("parallel");

    private final String wire;

    ConcurrencyMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static ConcurrencyMode fromWire(String wire) {
        for (ConcurrencyMode m : values()) {
            if (m.wire.equals(wire)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown concurrency: " + wire);
    }
}
