package com.tioka.system.api.obs;

import java.time.Instant;

/**
 * 数据去向报表行（OBS-07 / ADR-074 §4）：某种标签的数据被发往了哪个外部系统。
 *
 * <p>合规检查口径：{@code dataLabel × kind × counterparty} 的聚合，只统计出站（{@code direction = OUT}）。
 *
 * @param kind         台账类别（model / mcp / notify / http / biz / ext_kb / …）
 * @param counterparty 对方（外部系统 / 模型供应商）；出网兜底为主机名，可能为 {@code null}
 * @param dataLabel    数据标签；无标签的行归为 {@code null}
 * @param count        交互条数
 * @param errorCount   非 OK 条数（ERROR / TIMEOUT / REJECTED）
 * @param lastTs       最近一次交互时间
 */
public record DestinationRow(
        String kind,
        String counterparty,
        String dataLabel,
        long count,
        long errorCount,
        Instant lastTs) {
}
