package com.tioka.model.application.health;

/**
 * MH-10（ADR-041 第 5.1 节）：供应商/绑定/别名的运行健康状态（六态）。
 *
 * <p>判定优先级自高到低：{@link #DISABLED} → {@link #BUDGET_EXHAUSTED} → {@link #COOLDOWN}
 * → {@link #DOWN} → {@link #DEGRADED} → {@link #UP}（见 {@link ModelHealthEvaluator}）。
 */
public enum ProviderHealthState {

    /** 已停用。 */
    DISABLED,
    /** 供给侧配额已用完。 */
    BUDGET_EXHAUSTED,
    /** 冷却中（MH-03）。 */
    COOLDOWN,
    /** 主动探测连续失败，或短窗错误率过高。 */
    DOWN,
    /** 延迟劣化或 429 占比超阈。 */
    DEGRADED,
    /** 以上都不满足。 */
    UP;

    /** 由持久化列（{@code model_provider.health}）字符串还原；未知/空返回 {@code null}。 */
    public static ProviderHealthState fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ProviderHealthState s : values()) {
            if (s.name().equalsIgnoreCase(code.trim())) {
                return s;
            }
        }
        return null;
    }
}
