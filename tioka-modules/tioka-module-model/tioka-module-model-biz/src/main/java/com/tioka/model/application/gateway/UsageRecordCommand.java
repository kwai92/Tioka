package com.tioka.model.application.gateway;

import com.tioka.model.api.gateway.ModelCallContext;

/**
 * model_usage 入队命令（48-CS）。
 *
 * @param providerId 供给侧分摊维度（P2-L5 / V218）；由 binding 快照解析，可为 null
 * @param bindingVersionNo 使用的 binding 当前生效版本号（MH-08 / ADR-041 §2）；可为 null
 * @param aliasVersionNo 解析来源别名的当前生效版本号（MH-08）；无别名来源时为 null
 * @param planVersionNo 路由计划当前生效版本号（MH-08）；合成 HARD 时为 null
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
        String status,
        Integer bindingVersionNo,
        Integer aliasVersionNo,
        Integer planVersionNo
) {}
