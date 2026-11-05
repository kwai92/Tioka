package com.tioka.runtime.kernel.domain;

import com.tioka.model.api.ModelRouterApi;

import java.util.List;

public final class LoopContext {
    private final String threadId;
    private final String turnId;
    private final long leaseVersion;
    private final int maxSteps;
    private final int repeatLimit;
    private final int startStep;
    private final LoopPorts.SignalPort signals;
    private final String agentVersionId;
    private final String routingPlanId;
    private final String workspaceId;
    private boolean forceFinalAnswer;
    private ModelRouterApi.ModelSelection modelSelection;

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals) {
        this(threadId, turnId, leaseVersion, maxSteps, repeatLimit, startStep, signals, null, null, null);
    }

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals,
                       String agentVersionId, String routingPlanId, String workspaceId) {
        this.threadId = threadId;
        this.turnId = turnId;
        this.leaseVersion = leaseVersion;
        this.maxSteps = maxSteps;
        this.repeatLimit = repeatLimit;
        this.startStep = startStep;
        this.signals = signals;
        this.agentVersionId = agentVersionId;
        this.routingPlanId = routingPlanId;
        this.workspaceId = workspaceId;
    }

    public String threadId() { return threadId; }
    public String turnId() { return turnId; }
    public long leaseVersion() { return leaseVersion; }
    public int maxSteps() { return maxSteps; }
    public int repeatLimit() { return repeatLimit; }
    public int startStep() { return startStep; }
    public String agentVersionId() { return agentVersionId; }
    public String routingPlanId() { return routingPlanId; }
    public String workspaceId() { return workspaceId; }

    public ModelRouterApi.ModelSelection modelSelection() { return modelSelection; }
    public void setModelSelection(ModelRouterApi.ModelSelection modelSelection) {
        this.modelSelection = modelSelection;
    }

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
