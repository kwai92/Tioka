package com.tioka.governance.api.alert;

import java.time.Instant;
import java.util.List;

/**
 * 告警信号源 SPI（GOV-13 / ADR-041 §5.3）。
 *
 * <p>由承载数据的模块（配置框架、配额、运行状态 MH-10）实现；治理模块只做规则求值、去重、通知。
 * 经 Spring {@code ObjectProvider} 可选装配：缺席时该条件视为「无数据」并跳过。
 */
public interface AlertSignalProvider {

    /** 是否支持该条件。 */
    boolean supports(AlertConditionType condition);

    /**
     * 求值：返回该规则在当前时刻的观测列表。
     *
     * <ul>
     *   <li>状态条件：{@code state} 为当前状态串（如 {@code DOWN}/{@code UP}）；</li>
     *   <li>指标条件：{@code value} 为窗口内聚合值；</li>
     *   <li>事件条件：每个「新发生」的事件返回一条观测（{@code observedAt} 为其发生时刻）。</li>
     * </ul>
     *
     * <p>返回空列表表示无数据 / 无新事件（跳过，不产生事件）。
     */
    List<AlertObservation> observe(AlertRuleRef rule, Instant now);

    /**
     * 单条观测。
     *
     * @param targetId   实际对象标识（{@code ALL} 规则逐对象展开时用于去重）
     * @param state      状态条件的状态串；非状态条件可空
     * @param value      指标条件的实测值；非指标条件可空
     * @param message    人类可读描述（写入事件与通知）
     * @param observedAt 观测时刻（事件条件为其发生时刻，其余为求值时刻）
     */
    record AlertObservation(
            String targetId,
            String state,
            Double value,
            String message,
            Instant observedAt) {

        public static AlertObservation ofValue(String targetId, double value, String message, Instant at) {
            return new AlertObservation(targetId, null, value, message, at);
        }

        public static AlertObservation ofState(String targetId, String state, String message, Instant at) {
            return new AlertObservation(targetId, state, null, message, at);
        }
    }
}
