package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysWorkspaceMapper;
import com.tioka.system.infrastructure.persistence.SysWorkspaceDO;
import org.springframework.stereotype.Repository;

@Repository
public class SysWorkspaceRepository {
    private final SysWorkspaceMapper mapper;

    public SysWorkspaceRepository(SysWorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    public SysWorkspaceDO findById(String id) {
        return mapper.selectById(id);
    }

    public void insert(SysWorkspaceDO row) {
        mapper.insert(row);
    }
}
