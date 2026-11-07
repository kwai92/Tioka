package com.tioka.model.application.gateway;

import com.tioka.model.api.gateway.ModelCallContext;

/** model_usage 入队命令（48-CS）。 */
public record UsageRecordCommand(
        ModelCallContext ctx,
        String bindingId,
        String modelRef,
        int promptTokens,
        int completionTokens,
        long inputPriceMicros,
        long outputPriceMicros,
        int latencyMs,
        String status
) {}
