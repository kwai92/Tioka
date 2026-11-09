package com.tioka.model.application.routing;

import com.tioka.model.api.routing.RoutingMode;

import java.util.List;
import java.util.Map;

/**
 * 路由计划 spec 的运行时快照（ADR-024 RoutingPlanSpec 解析结果）。
 *
 * @param legacy               true 表示 V1 平铺 spec（无 mode 字段，ModelHubSpecs.planSpec 结构）；
 *                             执行侧对此类计划回退既有 primary/fallback 解析路径
 * @param mode                 路由模式
 * @param groups               组名 → 有序候选 bindingId（FREE_FIRST 约定含 free 组、主力组默认 main）
 * @param categoryToGroup      BRANCH：任务类别 → 组
 * @param difficultyToGroup    DIFFICULTY：难度（EASY/MEDIUM/HARD）→ 组
 * @param decision             决策器配置；FIXED 模式为 null
 * @param escalation           FREE_FIRST 升级策略：免费组耗尽后升 mainGroup；mainGroup 为 null 表示直接失败
 * @param preferPreviousInGroup 新组与上一 Turn 同组时优先沿用上一模型（ADR-024：不得默认关闭）
 */
public record RoutingPlanSnapshot(boolean legacy,
                                  RoutingMode mode,
                                  Map<String, List<String>> groups,
                                  Map<String, String> categoryToGroup,
                                  Map<String, String> difficultyToGroup,
                                  DecisionConfig decision,
                                  EscalationPolicy escalation,
                                  boolean preferPreviousInGroup) {

    /** 决策器配置（ADR-024 DecisionConfig）。 */
    public record DecisionConfig(String deciderId, String bindingId, int timeoutMs, String fallbackGroup) {}

    /** FREE_FIRST 升级策略：免费组耗尽 → mainGroup；mainGroup 为 null 表示直接失败。 */
    public record EscalationPolicy(String freeGroup, String mainGroup) {}

    public List<String> groupCandidates(String groupName) {
        if (groupName == null) {
            return List.of();
        }
        List<String> c = groups.get(groupName);
        return c == null ? List.of() : List.copyOf(c);
    }

    /** 默认主力组名：escalation.mainGroup，否则 main，否则第一个组。 */
    public String mainGroup() {
        if (escalation != null && escalation.mainGroup() != null && groups.containsKey(escalation.mainGroup())) {
            return escalation.mainGroup();
        }
        if (groups.containsKey("main")) {
            return "main";
        }
        return groups.isEmpty() ? null : groups.keySet().iterator().next();
    }

    /** FREE_FIRST 的起始免费组：escalation.freeGroup，否则 free，否则主力组。 */
    public String freeGroup() {
        if (escalation != null && escalation.freeGroup() != null && groups.containsKey(escalation.freeGroup())) {
            return escalation.freeGroup();
        }
        if (groups.containsKey("free")) {
            return "free";
        }
        return mainGroup();
    }
}
