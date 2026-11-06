package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.GovQuotaMapper;
import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class GovQuotaRepository {
    private final GovQuotaMapper mapper;

    public GovQuotaRepository(GovQuotaMapper mapper) {
        this.mapper = mapper;
    }

    public GovQuotaDO find(String scopeType, String scopeId, String metric) {
        return mapper.find(scopeType, scopeId, metric);
    }

    public List<GovQuotaDO> listByScope(String scopeType, String scopeId) {
        return mapper.listByScope(scopeType, scopeId);
    }

    public List<GovQuotaDO> listByMetric(String metric) {
        return mapper.listByMetric(metric);
    }

    public void upsertLimit(String scopeType, String scopeId, String metric, long limitValue, String actor) {
        GovQuotaDO row = new GovQuotaDO();
        row.setScopeType(scopeType);
        row.setScopeId(scopeId);
        row.setMetric(metric);
        row.setLimitValue(limitValue);
        row.setUsedValue(0L);
        row.setCreateBy(actor);
        row.setUpdateBy(actor);
        mapper.upsertLimit(row);
    }

    public void updateUsed(String scopeType, String scopeId, String metric,
                           long usedValue, OffsetDateTime windowStart, String actor) {
        mapper.updateUsed(scopeType, scopeId, metric, usedValue, windowStart, actor);
    }

    public void delete(String scopeType, String scopeId, String metric) {
        mapper.delete(scopeType, scopeId, metric);
    }
}
