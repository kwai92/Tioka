package com.tioka.system.api.obs;

import java.time.Instant;

/**
 * 外部交互台账查询结果行（OBS-07 / ADR-074 §4）。
 *
 * <p>只读投影，覆盖 ADR-074 §1 的关联键、对方回执号与内容哈希，供「输入对方单号，找到我方全过程」
 * 与数据去向报表使用；{@code payload_ref}（载荷对象存储引用）不出查询面。
 *
 * @param kind            台账类别：im / webhook / http / notify / biz / ext_kb / mcp / model / parse / a2a / datasource
 * @param counterparty    连接器 / 渠道实例 ID，或外部系统 / 模型供应商
 * @param externalRef     对方回执号（飞书 message_id / 业务单号等）
 * @param requestDigest   请求内容 SHA-256（纠纷取证用）
 * @param responseDigest  响应内容 SHA-256
 * @param dataLabel       数据标签（数据去向报表维度）
 * @param attrs          超出固定列的附加关联键（原样透出，未结构化）
 */
public record ExtInteractionView(
        String id,
        Instant ts,
        String direction,
        String kind,
        String counterparty,
        String endpoint,
        String operation,
        String authMode,
        String actorId,
        String traceId,
        String requestId,
        String subject,
        String turnId,
        String runId,
        String changeSetId,
        String toolCallId,
        String idempotencyKey,
        String externalRef,
        String dedupeKey,
        String requestDigest,
        String responseDigest,
        Long requestSize,
        Long responseSize,
        String dataLabel,
        String status,
        Integer httpStatus,
        String errorCode,
        Integer attempt,
        Long latencyMs,
        String retentionClass,
        String attrs) {
}
