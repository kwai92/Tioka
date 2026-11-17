package com.tioka.governance.application.alert;

import java.util.ArrayList;
import java.util.List;

/**
 * GOV-13 告警配置（{@code tioka.alerts.*}，ADR-041 §5.3）。
 *
 * <p>默认开启（与 {@code tioka.policy} / {@code tioka.obs.timeline} 一致）；
 * {@code tioka.alerts.enabled=false} 为紧急开关。
 */
public class AlertProperties {

    private boolean enabled = true;
    /** 求值间隔（毫秒），默认 30s（ADR-041 §5.3）。 */
    private long evaluateIntervalMs = 30_000L;
    /** 单实例求值锁 TTL（毫秒），须小于求值间隔。 */
    private long lockTtlMs = 25_000L;
    /** 事件历史单次查询上限。 */
    private int eventQueryLimit = 200;
    /** 规则未指定接收人时的系统缺省接收人；为空则不外发通知（只入事件）。 */
    private List<String> defaultRecipients = new ArrayList<>();
    /** 静默时段判定所用业务时区（ADR-041 §5.2）。 */
    private String silenceZone = "Asia/Shanghai";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getEvaluateIntervalMs() { return evaluateIntervalMs; }
    public void setEvaluateIntervalMs(long evaluateIntervalMs) { this.evaluateIntervalMs = evaluateIntervalMs; }
    public long getLockTtlMs() { return lockTtlMs; }
    public void setLockTtlMs(long lockTtlMs) { this.lockTtlMs = lockTtlMs; }
    public int getEventQueryLimit() { return eventQueryLimit; }
    public void setEventQueryLimit(int eventQueryLimit) { this.eventQueryLimit = eventQueryLimit; }
    public List<String> getDefaultRecipients() { return defaultRecipients; }
    public void setDefaultRecipients(List<String> defaultRecipients) {
        this.defaultRecipients = defaultRecipients == null ? new ArrayList<>() : defaultRecipients;
    }
    public String getSilenceZone() { return silenceZone; }
    public void setSilenceZone(String silenceZone) { this.silenceZone = silenceZone; }
}
