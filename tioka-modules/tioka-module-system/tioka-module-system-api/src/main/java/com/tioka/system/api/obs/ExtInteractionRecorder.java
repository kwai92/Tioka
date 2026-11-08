package com.tioka.system.api.obs;

import java.util.Map;

/**
 * 外部交互台账写入（OBS-04a / ADR-074）。
 * 飞书回调与发送、Webhook、{@link com.tioka.system.api.spi.EgressClient} 出站均经此记录。
 */
public interface ExtInteractionRecorder {

    void record(ExtInteractionRecord record);

    enum Direction { IN, OUT }

    enum Status { OK, ERROR, TIMEOUT, REJECTED, DUPLICATE }

    /**
     * @param kind          见 ADR-074：{@code im}/{@code webhook}/{@code http}/…
     * @param counterparty  连接器或渠道实例 ID
     * @param endpoint      主机 + 路径模板
     * @param operation     如 {@code im.message.send}
     * @param externalRef   对方回执号，可空
     * @param dedupeKey     入站去重键，可空
     * @param attrs         附加关联键（request_id、subject、turn_id 等），可空
     */
    record ExtInteractionRecord(
            Direction direction,
            String kind,
            String counterparty,
            String endpoint,
            String operation,
            Status status,
            Integer httpStatus,
            String externalRef,
            String dedupeKey,
            Long latencyMs,
            Map<String, String> attrs) {

        public ExtInteractionRecord {
            attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        }
    }
}
