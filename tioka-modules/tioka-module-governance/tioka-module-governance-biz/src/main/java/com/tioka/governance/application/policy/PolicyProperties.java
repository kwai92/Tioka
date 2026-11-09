package com.tioka.governance.application.policy;

import com.tioka.governance.api.policy.PolicyMode;

/**
 * GOV-07 配置（M3-05 §1.7）。{@code tioka.policy.enabled} 默认 true：关闭后第 9 步直接 ALLOW，
 * 仅用于故障时的紧急开关，切换写审计。
 */
public class PolicyProperties {

    private boolean enabled = true;
    /** 单次求值超时（毫秒）。 */
    private long evalTimeoutMs = 20;
    /** 缓存兜底到期（秒）。 */
    private long cacheTtlSeconds = 60;
    /** 导入策略的默认模式。 */
    private PolicyMode importDefaultMode = PolicyMode.DRYRUN;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getEvalTimeoutMs() { return evalTimeoutMs; }
    public void setEvalTimeoutMs(long evalTimeoutMs) { this.evalTimeoutMs = evalTimeoutMs; }
    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(long cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public PolicyMode getImportDefaultMode() { return importDefaultMode; }
    public void setImportDefaultMode(PolicyMode importDefaultMode) { this.importDefaultMode = importDefaultMode; }
}
