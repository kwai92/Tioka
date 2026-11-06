package com.tioka.runtime.kernel.domain;

import java.util.List;
import java.util.Map;

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
    private final String principalId;
    private final String approvalPolicy;
    private final String origin;
    private final List<String> toolAllowList;
    private final Map<String, Object> agentToolConfig;
    private boolean forceFinalAnswer;

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals) {
        this(threadId, turnId, leaseVersion, maxSteps, repeatLimit, startStep, signals,
                null, null, null, null, null, null, null, null);
    }

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals,
                       String agentVersionId, String routingPlanId, String workspaceId) {
        this(threadId, turnId, leaseVersion, maxSteps, repeatLimit, startStep, signals,
                agentVersionId, routingPlanId, workspaceId, null, null, null, null, null);
    }

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals,
                       String agentVersionId, String routingPlanId, String workspaceId,
                       String principalId) {
        this(threadId, turnId, leaseVersion, maxSteps, repeatLimit, startStep, signals,
                agentVersionId, routingPlanId, workspaceId, principalId, null, null, null, null);
    }

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals,
                       String agentVersionId, String routingPlanId, String workspaceId,
                       String principalId, String approvalPolicy) {
        this(threadId, turnId, leaseVersion, maxSteps, repeatLimit, startStep, signals,
                agentVersionId, routingPlanId, workspaceId, principalId, approvalPolicy,
                null, null, null);
    }

    public LoopContext(String threadId, String turnId, long leaseVersion,
                       int maxSteps, int repeatLimit, int startStep,
                       LoopPorts.SignalPort signals,
                       String agentVersionId, String routingPlanId, String workspaceId,
                       String principalId, String approvalPolicy,
                       String origin, List<String> toolAllowList,
                       Map<String, Object> agentToolConfig) {
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
        this.principalId = principalId;
        this.approvalPolicy = approvalPolicy;
        this.origin = origin;
        this.toolAllowList = toolAllowList == null ? List.of() : List.copyOf(toolAllowList);
        this.agentToolConfig = agentToolConfig == null ? Map.of() : Map.copyOf(agentToolConfig);
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
    public String principalId() { return principalId; }

    /** TurnOptions.approvalPolicy；空则用 agent-config。 */
    public String approvalPolicy() { return approvalPolicy; }

    public String origin() { return origin; }

    public List<String> toolAllowList() { return toolAllowList; }

    /** CON-01：含 mcpGrants，供 ToolGateway 授权。 */
    public Map<String, Object> agentToolConfig() { return agentToolConfig; }

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
