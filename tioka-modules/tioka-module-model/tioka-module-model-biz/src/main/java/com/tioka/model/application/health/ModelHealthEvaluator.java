package com.tioka.model.application.health;

/**
 * MH-10（ADR-041 第 5.1 节）：健康状态判定（纯函数，可离线单测）。
 *
 * <p>判定表（自上而下，首个命中即结论）：
 * <ul>
 *     <li>{@code DISABLED}：已停用；</li>
 *     <li>{@code BUDGET_EXHAUSTED}：供给侧配额已用完；</li>
 *     <li>{@code COOLDOWN}：MH-03 冷却中；</li>
 *     <li>{@code DOWN}：主动探测连续 3 次失败，或 5 分钟内错误率 &gt; 50%（至少 10 次调用）；</li>
 *     <li>{@code DEGRADED}：P95 延迟超过近 24 小时基线的 2 倍，或 429 占比 &gt; 10%；</li>
 *     <li>{@code UP}：以上都不满足。</li>
 * </ul>
 */
public final class ModelHealthEvaluator {

    /** 主动探测连续失败阈值。 */
    public static final int DOWN_PROBE_FAILURES = 3;
    /** 短窗错误率阈值（DOWN）。 */
    public static final double DOWN_ERROR_RATE = 0.50d;
    /** 短窗样本下限（低于此不判 DOWN）。 */
    public static final long DOWN_MIN_CALLS = 10L;
    /** 延迟劣化倍数（DEGRADED）。 */
    public static final double DEGRADED_LATENCY_FACTOR = 2.0d;
    /** 429 占比阈值（DEGRADED）。 */
    public static final double DEGRADED_429_RATIO = 0.10d;

    private ModelHealthEvaluator() {
    }

    public static ProviderHealthState evaluate(ModelHealthSignals s) {
        if (s == null) {
            return ProviderHealthState.UP;
        }
        if (s.disabled()) {
            return ProviderHealthState.DISABLED;
        }
        if (s.budgetExhausted()) {
            return ProviderHealthState.BUDGET_EXHAUSTED;
        }
        if (s.cooling()) {
            return ProviderHealthState.COOLDOWN;
        }
        if (s.probeConsecutiveFailures() >= DOWN_PROBE_FAILURES) {
            return ProviderHealthState.DOWN;
        }
        if (s.calls5m() >= DOWN_MIN_CALLS && s.errorRate5m() > DOWN_ERROR_RATE) {
            return ProviderHealthState.DOWN;
        }
        if (degradedByLatency(s)) {
            return ProviderHealthState.DEGRADED;
        }
        if (s.ratio429() > DEGRADED_429_RATIO) {
            return ProviderHealthState.DEGRADED;
        }
        return ProviderHealthState.UP;
    }

    /** P95 延迟超过近 24 小时基线的 {@value #DEGRADED_LATENCY_FACTOR} 倍（基线缺失时不判）。 */
    private static boolean degradedByLatency(ModelHealthSignals s) {
        return s.p95LatencyMs() > 0d
                && s.baselineP95LatencyMs() > 0d
                && s.p95LatencyMs() > DEGRADED_LATENCY_FACTOR * s.baselineP95LatencyMs();
    }
}
