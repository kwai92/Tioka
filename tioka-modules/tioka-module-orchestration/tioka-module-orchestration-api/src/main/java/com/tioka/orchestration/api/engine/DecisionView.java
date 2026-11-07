package com.tioka.orchestration.api.engine;

/**
 * 单条规则决定视图（落库 {@code orch_decision} 的契约形状）。
 *
 * @param decisionId     决定 ID
 * @param eventId        事件 ID
 * @param ruleVersionId  规则版本 ID
 * @param strength       显式 / 推断
 * @param decision       决定码
 * @param reason         原因，可空
 * @param workItemId     ACCEPT 后关联工单，可空
 */
public record DecisionView(
        String decisionId,
        String eventId,
        String ruleVersionId,
        OrchApi.Strength strength,
        DecisionCode decision,
        String reason,
        String workItemId) {
}
