package com.tioka.flow.application.exec;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.SystemPrincipals;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次 Flow 运行的节点执行上下文。
 *
 * <p>FLW-02：{@code runAs} 为本次 run 发起身份（对应文档 {@code flowRun.runAs}）；
 * {@code nodeRunId} 为当前节点执行实例 ID（作 ModelGateway 冻结键 turnId）。
 */
public final class NodeExecContext {
    private final String runId;
    private final Principal runAs;
    private final Map<String, Object> runInput;
    /** DAT-04：Flow Run 携带的调用方授予 ID（ADR-029 第 47 条）。 */
    private final String grantId;
    private final Map<String, Object> outputsByNode = new ConcurrentHashMap<>();
    private volatile String currentNodeRunId;
    private volatile Object terminalOutput;
    private volatile boolean suspended;
    private volatile String attentionId;

    public NodeExecContext(String runId, Map<String, Object> runInput) {
        this(runId, SystemPrincipals.system(), runInput, null);
    }

    public NodeExecContext(String runId, Principal runAs, Map<String, Object> runInput) {
        this(runId, runAs, runInput, null);
    }

    public NodeExecContext(String runId, Principal runAs, Map<String, Object> runInput, String grantId) {
        this.runId = runId;
        this.runAs = runAs == null ? SystemPrincipals.system() : runAs;
        this.runInput = runInput == null ? Map.of() : runInput;
        this.grantId = grantId;
    }

    public String runId() { return runId; }

    /** 本 run 发起人（配额 / usage 归属）。 */
    public Principal runAs() { return runAs; }

    /** DAT-04：调用方授予 ID；API 形态为服务账号根授予，null = 旧路径。 */
    public String grantId() { return grantId; }

    public Map<String, Object> runInput() { return runInput; }

    /** 当前节点执行实例 ID；在 {@link #beginNodeRun(String)} 后可读。 */
    public String nodeRunId() { return currentNodeRunId; }

    public void beginNodeRun(String nodeRunId) {
        this.currentNodeRunId = nodeRunId;
    }

    public void putOutput(String nodeId, Object output) {
        if (nodeId != null) outputsByNode.put(nodeId, output);
    }

    public Object outputOf(String nodeId) { return outputsByNode.get(nodeId); }

    public void markTerminal(Object output) { this.terminalOutput = output; }

    public Object terminalOutput() { return terminalOutput; }

    public void markSuspended(String attentionId) {
        this.suspended = true;
        this.attentionId = attentionId;
    }

    public boolean suspended() { return suspended; }

    public String attentionId() { return attentionId; }
}
