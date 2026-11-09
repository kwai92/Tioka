package com.tioka.model.api.routing;

/**
 * MH-06（ADR-024）路由计划的四种模式。
 * <ul>
 *   <li>{@code FIXED}：不调决策器，固定候选（既有 primary/fallback 语义）</li>
 *   <li>{@code DIFFICULTY}：决策器输出难度，按 difficultyToGroup 映射组</li>
 *   <li>{@code BRANCH}：决策器输出任务类别，按 categoryToGroup 映射组</li>
 *   <li>{@code FREE_FIRST}：先试免费组，失败按计划升主力组或直接失败</li>
 * </ul>
 */
public enum RoutingMode {
    FIXED, DIFFICULTY, BRANCH, FREE_FIRST
}
