package com.tioka.model.application.gateway;

import com.tioka.model.api.gateway.ModelCallContext;

/**
 * model_usage 入队命令（48-CS）。
 *
 * @param providerId 供给侧分摊维度（P2-L5 / V218）；由 binding 快照解析，可为 null
 */
public record UsageRecordCommand(
        ModelCallContext ctx,
        String bindingId,
        String providerId,
        String modelRef,
        int promptTokens,
        int completionTokens,
        long inputPriceMicros,
        long outputPriceMicros,
        int latencyMs,
        String status
) {}
