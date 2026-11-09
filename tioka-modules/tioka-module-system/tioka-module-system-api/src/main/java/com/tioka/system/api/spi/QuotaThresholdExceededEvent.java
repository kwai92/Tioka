package com.tioka.system.api.spi;

/**
 * 配额阈值预警领域事件（GAP-L5-3 / P2）。
 *
 * <p>当某 scope 的月度 token 用量首次越过 {@code limitValue} 的预警阈值（当前 80%）
 * 时由 {@link QuotaGuard} 实现发布（Spring 应用内事件）。
 * 订阅方：{@code QuotaWarnNotifier}（日志 + {@code NotificationPort} 可选通知）；
 * MQ outbox 对接（新 topic）随 GOV-13 / NTF-01 接线时启用，信封契约不动。
 *
 * @param scopeType   workspace / user / agent
 * @param scopeId     scope 标识
 * @param metric      目前仅 {@code tokens_month}
 * @param limitValue  月度上限
 * @param usedValue   触发时的已用量
 * @param thresholdPct 预警阈值百分比（如 80）
 * @param yearMonth   归属月窗（yyyy-MM）
 */
public record QuotaThresholdExceededEvent(
        String scopeType,
        String scopeId,
        String metric,
        long limitValue,
        long usedValue,
        int thresholdPct,
        String yearMonth) {}
