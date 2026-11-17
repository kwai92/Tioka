package com.tioka.system.api.obs;

/**
 * 按对方与 {@code kind} 的调用统计（OBS-07 / ADR-074 §4）：调用量、失败率、P95 时延。
 *
 * @param kind         台账类别
 * @param counterparty 对方
 * @param count        调用条数
 * @param errorCount   非 OK 条数
 * @param errorRate    失败率（{@code errorCount / count}，0〜1）
 * @param p95LatencyMs P95 时延（毫秒）；无时延样本为 {@code null}
 */
public record CounterpartyStat(
        String kind,
        String counterparty,
        long count,
        long errorCount,
        double errorRate,
        Double p95LatencyMs) {
}
