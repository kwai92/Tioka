package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.EvidencePackMapper;
import com.tioka.system.infrastructure.persistence.EvidencePackDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class EvidencePackRepositoryImpl implements EvidencePackRepository {

    private final EvidencePackMapper mapper;

    public EvidencePackRepositoryImpl(EvidencePackMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(EvidencePackDO row) {
        mapper.insert(row);
    }

    @Override
    public List<EvidencePackDO> listByScope(String scopeType, String scopeRef, String workspaceId, int limit) {
        int safe = Math.min(Math.max(limit, 1), 200);
        return mapper.listByScope(scopeType, scopeRef, workspaceId, safe);
    }
}
