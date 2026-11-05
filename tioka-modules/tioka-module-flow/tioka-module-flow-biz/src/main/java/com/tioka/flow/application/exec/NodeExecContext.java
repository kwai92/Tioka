package com.tioka.flow.application.exec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeExecContext {
    private final String runId;
    private final Map<String, Object> runInput;
    private final Map<String, Object> outputsByNode = new ConcurrentHashMap<>();
    private volatile Object terminalOutput;
    private volatile boolean suspended;
    private volatile String attentionId;

    public NodeExecContext(String runId, Map<String, Object> runInput) {
        this.runId = runId;
        this.runInput = runInput == null ? Map.of() : runInput;
    }

    public String runId() { return runId; }

    public Map<String, Object> runInput() { return runInput; }

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
