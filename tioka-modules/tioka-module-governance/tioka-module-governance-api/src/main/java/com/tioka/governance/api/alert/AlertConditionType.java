package com.tioka.governance.api.alert;

/**
 * 告警条件（GOV-13 / ADR-041 §5.3）。
 *
 * <p>三类：状态条件（{@link #HEALTH_STATUS}，与 {@code threshold_text} 比较）、
 * 指标条件（数值，与 {@code comparator} + {@code threshold_value} 比较）、
 * 事件条件（{@link #CONFIG_PUBLISHED}/{@link #CONFIG_ROLLED_BACK}，每次发生即知会，不做状态去重）。
 */
public enum AlertConditionType {
    /** 健康状态变为某值（阈值取 {@code threshold_text}，如 {@code DOWN}）。 */
    HEALTH_STATUS,
    /** 错误率（窗口内 ERROR 调用 / 全部调用）超过阈值。 */
    ERROR_RATE,
    /** P95 延迟（毫秒）超过阈值。 */
    P95_LATENCY,
    /** 故障接力率（FAILOVER 路由事件 / 全部路由事件）超过阈值。 */
    FAILOVER_RATE,
    /** 配额越过预警比例（缺省 0.8）。 */
    QUOTA_WARN,
    /** 配额用完（比例达到 1.0）。 */
    QUOTA_EXHAUSTED,
    /** 有配置被发布（知会，不做状态去重）。 */
    CONFIG_PUBLISHED,
    /** 有配置被回滚（知会，不做状态去重）。 */
    CONFIG_ROLLED_BACK,
    /**
     * 结构化输出首次通过率（本窗口均值相对上一等长窗口）下降超过阈值，单位为百分点（SCH-10 / ADR-066 §10.1）。
     *
     * <p>缺省口径：某（Schema、模型）首次通过率 7 日均值下降超过 5 个百分点；窗口取规则 {@code window_minutes}。
     */
    STRUCTURED_FIRST_PASS_DROP,
    /**
     * 结构化输出失败率超过阈值（比例，0〜1）（SCH-10 / ADR-066 §10.1）。
     *
     * <p>缺省口径：某（Schema、模型）失败率超过 1%。
     */
    STRUCTURED_FAILURE_RATE;

    /** 状态条件：比较对象为状态串。 */
    public boolean isStateCondition() {
        return this == HEALTH_STATUS;
    }

    /** 事件条件：每次发生即产生一条 FIRING 事件，无恢复语义。 */
    public boolean isEventCondition() {
        return this == CONFIG_PUBLISHED || this == CONFIG_ROLLED_BACK;
    }

    /** 指标条件：比较对象为窗口内聚合的数值。 */
    public boolean isMetricCondition() {
        return !isStateCondition() && !isEventCondition();
    }
}
