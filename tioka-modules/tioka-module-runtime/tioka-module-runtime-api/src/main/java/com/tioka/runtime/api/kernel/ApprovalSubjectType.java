package com.tioka.runtime.api.kernel;

/** 通用审批表 runtime_approval 的挂起对象（ADR-029 第 1 条）。 */
public enum ApprovalSubjectType {
    TURN("turn"),
    FLOW_NODE_RUN("flow_node_run"),
    ORCH_RUN("orch_run");

    private final String wire;

    ApprovalSubjectType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
