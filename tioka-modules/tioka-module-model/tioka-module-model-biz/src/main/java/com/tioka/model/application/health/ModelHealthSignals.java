package com.tioka.model.application.health;

/**
 * MH-10 健康判定输入信号（纯数据，便于离线单测）。
 *
 * @param disabled                 是否已停用
 * @param budgetExhausted          供给侧配额是否已用完
 * @param cooling                  是否处于 MH-03 冷却中
 * @param probeConsecutiveFailures 主动探测连续失败次数
 * @param calls5m                  近 5 分钟调用数
 * @param errors5m                 近 5 分钟错误数
 * @param calls429_5m              近 5 分钟 429 次数
 * @param p95LatencyMs             近 5 分钟 P95 延迟（毫秒）
 * @param baselineP95LatencyMs     近 24 小时基线 P95 延迟（毫秒）
 */
public record ModelHealthSignals(boolean disabled,
                                 boolean budgetExhausted,
                                 boolean cooling,
                                 int probeConsecutiveFailures,
                                 long calls5m,
                                 long errors5m,
                                 long calls429_5m,
                                 double p95LatencyMs,
                                 double baselineP95LatencyMs) {

    /** 近 5 分钟错误率（无调用记 0）。 */
    public double errorRate5m() {
        return calls5m <= 0 ? 0d : (double) errors5m / calls5m;
    }

    /** 近 5 分钟 429 占比（无调用记 0）。 */
    public double ratio429() {
        return calls5m <= 0 ? 0d : (double) calls429_5m / calls5m;
    }

    /** 全正常信号（缺省，用于未知作用对象）。 */
    public static ModelHealthSignals healthy() {
        return new ModelHealthSignals(false, false, false, 0, 0, 0, 0, 0d, 0d);
    }
}
