package com.tioka.governance.config;

/**
 * GOV-12 特性开关与对账间隔。默认关闭。
 */
public class ConfigVersioningProperties {

    /** 对应 {@code tioka.config.version.enabled}。 */
    private boolean enabled;
    /** 对账间隔毫秒，默认 30s（ADR-041 §2）。 */
    private long reconcileIntervalMs = 30_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }
}
