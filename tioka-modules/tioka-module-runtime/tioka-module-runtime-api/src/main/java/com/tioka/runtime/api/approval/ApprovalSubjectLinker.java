package com.tioka.runtime.api.approval;

/**
 * 审批主体跳转与来源渲染（约束 F27）。
 * 按 {@code subject_type}（{@code turn}/{@code orch_run}/{@code flow_node_run}）注册；
 * 各模块提供实现，审批台与卡片经此取来源名称与版本，之后不再改 K2 决定逻辑。
 */
public interface ApprovalSubjectLinker {

    /** 与 {@code runtime_approval.subject_type} 相同的小写值。 */
    String subjectType();

    ApprovalView.SubjectLink link(String subjectId, String titleHint);
}
