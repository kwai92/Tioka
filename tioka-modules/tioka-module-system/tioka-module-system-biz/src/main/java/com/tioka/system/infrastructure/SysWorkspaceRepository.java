package com.tioka.system.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.system.infrastructure.mapper.SysWorkspaceMapper;
import com.tioka.system.infrastructure.persistence.SysWorkspaceDO;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    /** HCI-03：租户级空间列表。 */
    public List<SysWorkspaceDO> listAll() {
        return mapper.selectList(Wrappers.<SysWorkspaceDO>lambdaQuery().orderByAsc(SysWorkspaceDO::getName));
    }
}
