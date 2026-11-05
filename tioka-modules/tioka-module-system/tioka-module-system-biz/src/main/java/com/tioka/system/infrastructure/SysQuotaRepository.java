package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysQuotaMapper;
import com.tioka.system.infrastructure.persistence.SysQuotaDO;
import org.springframework.stereotype.Repository;

@Repository
public class SysQuotaRepository {
    private final SysQuotaMapper mapper;

    public SysQuotaRepository(SysQuotaMapper mapper) {
        this.mapper = mapper;
    }

    public SysQuotaDO findByWorkspaceId(String workspaceId) {
        return mapper.selectById(workspaceId);
    }

    public void save(SysQuotaDO row) {
        if (mapper.selectById(row.getWorkspaceId()) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    public int tryAcquire(String workspaceId, int limit) {
        return mapper.tryAcquire(workspaceId, limit);
    }

    public void release(String workspaceId) {
        mapper.release(workspaceId);
    }

    public int consumeTokens(String workspaceId, int tokens, int tokenLimit) {
        return mapper.consumeTokens(workspaceId, tokens, tokenLimit);
    }
}
