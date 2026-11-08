package com.tioka.model.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.model.infrastructure.mapper.ModelUsageMapper;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public void insertBatch(List<ModelUsageDO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (ModelUsageDO row : rows) {
            mapper.insert(row);
        }
    }

    public List<ModelUsageDO> findBySessionId(String threadId) {
        return mapper.selectList(Wrappers.<ModelUsageDO>lambdaQuery()
                .eq(ModelUsageDO::getThreadId, threadId)
                .orderByAsc(ModelUsageDO::getCreateAt)
                .orderByAsc(ModelUsageDO::getId));
    }

    public List<ModelUsageDO> findByTurnId(String turnId) {
        return mapper.selectList(Wrappers.<ModelUsageDO>lambdaQuery()
                .eq(ModelUsageDO::getTurnId, turnId)
                .orderByAsc(ModelUsageDO::getStep)
                .orderByAsc(ModelUsageDO::getCreateAt)
                .orderByAsc(ModelUsageDO::getId));
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

    public Map<String, Object> sessionAggregate(String threadId) {
        return mapper.sessionAggregate(threadId);
    }

    public Long sumTokensByScope(String scopeType, String scopeId, Instant from, Instant to) {
        return mapper.sumTokensByScope(scopeType, scopeId, from, to);
    }
}

