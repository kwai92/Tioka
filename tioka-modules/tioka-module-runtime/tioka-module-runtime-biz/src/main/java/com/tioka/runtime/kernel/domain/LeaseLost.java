package com.tioka.runtime.kernel.domain;

/** 租约丢失：AgentLoop / TurnRunner 不得再写终态。 */
public final class LeaseLost extends RuntimeException {
    public LeaseLost() {
        super("LEASE_LOST");
    }
}
