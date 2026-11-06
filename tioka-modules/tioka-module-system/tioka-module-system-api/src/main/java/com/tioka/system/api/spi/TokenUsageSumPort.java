package com.tioka.system.api.spi;

import java.time.Instant;

/**
 * 供 {@code QuotaReconciler} 按 scope 汇总 {@code model_usage}（由 model-biz 实现）。
 */
public interface TokenUsageSumPort {

    /**
     * @param scopeType workspace / user / agent
     * @return prompt + completion 之和；无记录返回 0
     */
    long sumTokens(String scopeType, String scopeId, Instant from, Instant to);
}
