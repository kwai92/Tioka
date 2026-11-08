package com.tioka.orchestration.api.engine;

/**
 * 接单模式（ADR-020 修订第 2 条；表 {@code orch_rule_version.accept_mode}，wire 小写）。
 */
public enum AcceptMode {
    OFF("off"),
    OBSERVE("observe"),
    AUTO("auto");

    private final String wire;

    AcceptMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static AcceptMode fromWire(String wire) {
        for (AcceptMode m : values()) {
            if (m.wire.equals(wire)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown accept_mode: " + wire);
    }
}
