package com.tioka.system.api.obs;

import java.time.Instant;
import java.util.List;

/**
 * 外部交互台账查询出口（OBS-07 / ADR-074 §4）。只读；写入见 {@link ExtInteractionRecorder}。
 *
 * <p>台账为全局跨边界观测面（无租户列），调用方须自行做平台级授权收口。
 */
public interface ExtInteractionQueryApi {

    /**
     * 按关联键 / 对方 / 回执号 / 类别 / 时间段查询明细，按时间倒序。
     *
     * <p>「输入对方单号，找到我方全过程」：以 {@code externalRef} 为主键查，或用 {@code subject}
     * 列出某份合同被发往过哪些外部系统与模型（OBS-07 验收）。
     */
    List<ExtInteractionView> query(Query query);

    /**
     * 数据去向报表（ADR-074 §4）：只统计出站，按 {@code dataLabel × kind × counterparty} 聚合。
     */
    List<DestinationRow> destinationReport(String kind, String dataLabel, Instant from, Instant to);

    /**
     * 调用统计（ADR-074 §4）：按 {@code kind × counterparty} 聚合调用量、失败率、P95 时延。
     */
    List<CounterpartyStat> counterpartyStats(String kind, Instant from, Instant to);

    /**
     * 明细查询条件；字段为 {@code null} / 0 表示不过滤。
     */
    record Query(
            String requestId,
            String subject,
            String counterparty,
            String externalRef,
            String kind,
            String direction,
            Instant from,
            Instant to,
            int limit,
            int offset) {

        public Query {
            limit = limit <= 0 ? 50 : limit;
            offset = Math.max(offset, 0);
        }
    }
}
