package com.tioka.runtime.api.approval;

/**
 * 审批结果的续跑处理器（ADR-029 第 1 条，施工设计 M1-02）。
 *
 * <p>每种 subject 一个实现：{@code turn}（runtime-biz）、{@code flow_node_run}（flow-biz，FLW-03）、
 * {@code orch_run}（orchestration-biz，ORC-04）。M1 在审批提交事务提交后同步调用；M2 起由
 * {@code tioka.runtime.approval_decided.v1} 的消费者调用。实现必须幂等。
 */
public interface ApprovalSubjectHandler {

    /** 与 {@code runtime_approval.subject_type} 相同的小写值。 */
    String subjectType();

    /** 审批进入 APPROVED、REJECTED 或 EXPIRED 后调用。 */
    void onDecided(ApprovalView approval);
}
