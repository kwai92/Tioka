package com.tioka.model.infrastructure;

import com.tioka.model.infrastructure.mapper.ModelUsageMapper;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class ModelUsageRepository {
    private final ModelUsageMapper mapper;

    public ModelUsageRepository(ModelUsageMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(ModelUsageDO row) {
        mapper.insert(row);
    }

    public List<Map<String, Object>> summaryByWorkspace(String workspaceId, Instant from, Instant to) {
        return mapper.summaryByWorkspace(workspaceId, from, to);
    }

    public List<Map<String, Object>> summaryByAgent(String workspaceId, Instant from, Instant to) {
        return mapper.summaryByAgent(workspaceId, from, to);
    }

    public List<Map<String, Object>> summaryByUser(String workspaceId, Instant from, Instant to) {
        return mapper.summaryByUser(workspaceId, from, to);
    }

    public List<Map<String, Object>> summaryByModel(String workspaceId, Instant from, Instant to) {
        return mapper.summaryByModel(workspaceId, from, to);
    }

    public List<Map<String, Object>> trendByDay(String scopeType, String scopeId, String workspaceId,
                                                Instant from, Instant to) {
        return mapper.trendByDay(scopeType, scopeId, workspaceId, from, to);
    }

    public Map<String, Object> sessionAggregate(String sessionId) {
        return mapper.sessionAggregate(sessionId);
    }
}
