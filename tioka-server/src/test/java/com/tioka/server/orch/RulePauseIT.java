package com.tioka.server.orch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ORC-07（M2-00 第 75 行）规则自动暂停——❌ 缺口骨架，暂不执行。
 *
 * <p>缺口（2026-11-10，main = adc51c9）：规则「连续失败自动暂停 / 恢复」在生产代码中
 * 未实现。证据：
 * <ul>
 *   <li>{@code tioka-module-orchestration-biz} 全部 Java 源中无 pause/resume/autoPause/
 *       暂停 相关逻辑；规则版本 stage 仅 DRAFT/CANARY/STABLE/RETIRED
 *       （{@code OrchRuleAppService#publish} 94–139 行），无失败阈值驱动的状态。</li>
 *   <li>{@code OrchRuleAppService}（全文）无 enable/disable/pause 接口；
 *       {@code OrchRuleController} 同。</li>
 *   <li>设计规格：S2-Orchestration编排.md 第 28 行「熔断：hops TTL、连续失败停、
 *       预算熔断」——本项对应「连续失败停」；hops 部分已由 {@code HopsIT}（orchestration-biz）
 *       验证。FE-17（M2-00 第 100 行「规则暂停与恢复」）亦被此缺口阻塞。</li>
 * </ul>
 *
 * <p>本文件按既有 IT 惯例（{@code OrchTimerIT}：@SpringBootTest @Tag("it")，
 * 由 failsafe/CI 承接）放置；类级 @Disabled，未启动上下文，不影响默认构建。
 * 实现自动暂停后移除 @Disabled 并补齐断言，验收口径：
 *
 * <ol>
 *   <li>同一规则连续失败达到阈值（阈值来源以实现为准，如配置键或规则字段）→ 规则进入
 *       PAUSED；</li>
 *   <li>暂停期间 {@code OrchEngineRepository#findActiveRulesForEventType} 不再返回该规则，
 *       后续事件不再派发（{@code OrchDecideService.decide} 不被调用）；</li>
 *   <li>恢复操作（管理接口/控制台）后规则重新参与决定；</li>
 *   <li>暂停/恢复动作留审计（why 事实流）。</li>
 * </ol>
 */
@SpringBootTest
@Tag("it")
@Disabled("缺口：规则自动暂停（连续失败阈值）与 pause/resume 入口未实现——OrchRuleAppService/OrchEngineRepository 无相应逻辑；实现后移除 @Disabled 补齐断言")
class RulePauseIT {

    @Test
    void consecutiveFailuresPauseRule() {
        // 占位：规则连续失败达阈值 → PAUSED
    }

    @Test
    void pausedRuleNotDispatched() {
        // 占位：暂停期间 findActiveRulesForEventType 不返回该规则、decide 不被调用
    }

    @Test
    void resumeRestoresDispatch() {
        // 占位：恢复后规则重新参与决定
    }
}
