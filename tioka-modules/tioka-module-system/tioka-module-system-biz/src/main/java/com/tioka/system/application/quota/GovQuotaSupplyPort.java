package com.tioka.system.application.quota;

import com.tioka.system.api.spi.SupplyQuotaPort;
import com.tioka.system.infrastructure.GovQuotaRepository;
import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.springframework.stereotype.Component;

/**
 * {@link SupplyQuotaPort} 的 {@code gov_quota} 实现（MH-09）：只暴露上限，已用量由 model-biz 侧按
 * {@code model_usage} 聚合（供应商/别名维度），避免跨域读用量明细。
 */
@Component
public class GovQuotaSupplyPort implements SupplyQuotaPort {

    private final GovQuotaRepository quotas;

    public GovQuotaSupplyPort(GovQuotaRepository quotas) {
        this.quotas = quotas;
    }

    @Override
    public Long monthlyLimit(String scopeType, String scopeId, String metric) {
        if (scopeType == null || scopeId == null || metric == null
                || !QuotaMetrics.SUPPLY_SCOPES.contains(scopeType)) {
            return null;
        }
        GovQuotaDO row = quotas.find(scopeType, scopeId, metric);
        return row == null ? null : row.getLimitValue();
    }
}
