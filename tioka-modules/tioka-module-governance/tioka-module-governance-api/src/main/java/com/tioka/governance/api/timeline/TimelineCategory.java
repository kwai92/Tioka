package com.tioka.governance.api.timeline;

/**
 * 时间线条目类别（ADR-075 §2）。{@code RUN} 覆盖 Turn / 编排 Run / Flow Run 的开始与结束。
 */
public enum TimelineCategory {
    /** 请求进入：业务系统推送、上传、外部回调。 */
    INBOUND,
    /** Turn / Run / Flow 开始结束。 */
    RUN,
    /** 模型调用（按步合并）。 */
    MODEL,
    /** 工具调用（按步合并）。 */
    TOOL,
    /** 决策：策略、规则求值、护栏。 */
    DECISION,
    /** 审批。 */
    APPROVAL,
    /** 变更：变更单、提交、撤销。 */
    CHANGE,
    /** 外发：外部交互台账出口。 */
    OUTBOUND,
    /** 通知。 */
    NOTIFY,
    /** 反馈。 */
    FEEDBACK,
    /** 配置变更（主体时间线的背景条目）。 */
    CONFIG,
    /** 安全事件。 */
    SECURITY
}
