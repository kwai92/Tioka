package com.tioka.model.application;

import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.system.api.spi.TokenUsageSumPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * GOV-03：按 scope 汇总 {@code model_usage} 供配额对账。
 */
@Component
public class ModelUsageSumAdapter implements TokenUsageSumPort {
    private final ModelUsageRepository usages;

    public ModelUsageSumAdapter(ModelUsageRepository usages) {
        this.usages = usages;
    }

    @Override
    public long sumTokens(String scopeType, String scopeId, Instant from, Instant to) {
        if (scopeType == null || scopeId == null || scopeId.isBlank()) {
            return 0L;
        }
        Long n = usages.sumTokensByScope(scopeType, scopeId, from, to);
        return n == null ? 0L : n;
    }
}
