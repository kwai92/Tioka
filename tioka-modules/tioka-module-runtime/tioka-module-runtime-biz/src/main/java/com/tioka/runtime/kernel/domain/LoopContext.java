package com.tioka.runtime.kernel.domain;

import java.util.List;

public final class LoopContext {
    private final String threadId;
    private final String turnId;
    private final long leaseVersion;
    private final int maxSteps;
    private final int repeatLimit;
    private final int startStep;
    private final LoopPorts.SignalPort signals;
    private boolean forceFinalAnswer;

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals) {
        this.threadId = threadId;
        this.turnId = turnId;
        this.leaseVersion = leaseVersion;
        this.maxSteps = maxSteps;
        this.repeatLimit = repeatLimit;
        this.startStep = startStep;
        this.signals = signals;
    }

    public String threadId() { return threadId; }
    public String turnId() { return turnId; }
    public long leaseVersion() { return leaseVersion; }
    public int maxSteps() { return maxSteps; }
    public int repeatLimit() { return repeatLimit; }
    public int startStep() { return startStep; }

    public void checkpoint() {
        signals.checkpoint(this);
    }

    public List<String> pollSteers() {
        return signals.pollSteers(this);
    }

    public void forceFinalAnswer() {
        this.forceFinalAnswer = true;
    }

    public boolean isForceFinalAnswer() {
        return forceFinalAnswer;
    }
}
