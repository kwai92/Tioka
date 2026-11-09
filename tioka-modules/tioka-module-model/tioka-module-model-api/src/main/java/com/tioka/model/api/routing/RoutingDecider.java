package com.tioka.model.api.routing;

import java.util.Map;

/**
 * MH-06（ADR-024）决策器 SPI：只在 Turn 开始时调用一次（上下文压缩后允许重新决策）。
 * <p>决策器实现禁止发起网络调用以外的副作用；实现经 Spring 装配后由
 * {@code RoutingDeciderRegistry} 按 {@link #id()} 发现。
 * <p>内置：{@code heuristic}（启发式）、{@code llm}（大模型决策）、{@code http}（外部 HTTP 决策服务）。
 */
public interface RoutingDecider {

    /** 决策器标识：heuristic / llm / http:{name}。 */
    String id();

    Decision decide(DecisionInput input);

    /**
     * @param userText            本 Turn 用户输入文本
     * @param previousGroup       上一 Turn 实际使用的路由组；无记录时为 null
     * @param previousCompetence  上一 Turn 选中 binding 的胜任度分（0–1）；未记录时为 null
     * @param hints               调用方附加信号（purpose、toolsRequired、imageRequired 等）
     */
    record DecisionInput(String userText, String previousGroup, Double previousCompetence,
                         Map<String, Object> hints) {}

    /**
     * @param category   任务类别（BRANCH 模式按 categoryToGroup 映射组）
     * @param difficulty 任务难度（DIFFICULTY 模式按 difficultyToGroup 映射组）
     * @param confidence 置信度 0–1
     * @param reason     决策理由，写入路由时间线
     */
    record Decision(String category, Difficulty difficulty, double confidence, String reason) {}
}
