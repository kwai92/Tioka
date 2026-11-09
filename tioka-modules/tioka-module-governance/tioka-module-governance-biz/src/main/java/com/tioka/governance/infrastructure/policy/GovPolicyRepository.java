package com.tioka.governance.infrastructure.policy;

import com.tioka.governance.infrastructure.mapper.GovPolicyMapper;
import com.tioka.governance.infrastructure.persistence.GovPolicyDO;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * gov_policy 仓储（application 经本类访问，禁止直持 Mapper；M3-05 PolicyStore 底座）。
 */
@Repository
public class GovPolicyRepository {

    private final GovPolicyMapper mapper;

    public GovPolicyRepository(GovPolicyMapper mapper) {
        this.mapper = mapper;
    }

    /** 参与求值的策略：系统级（workspace_id NULL）+ 当前空间，ACTIVE。 */
    public List<GovPolicyDO> listActiveForWorkspace(String workspaceId) {
        return mapper.listActiveForWorkspace(workspaceId);
    }

    public List<GovPolicyDO> listBySource(String workspaceId, String source) {
        return mapper.listBySource(workspaceId, source);
    }

    public GovPolicyDO findById(String id) {
        return mapper.findById(id);
    }

    public int maxVersion(String workspaceId, String source) {
        return mapper.maxVersion(workspaceId, source);
    }

    public int activeVersion(String workspaceId, String source) {
        return mapper.activeVersion(workspaceId, source);
    }

    public void insert(GovPolicyDO row) {
        mapper.insert(row);
    }

    public int disableBySource(String workspaceId, String source) {
        return mapper.disableBySource(workspaceId, source);
    }

    public int disable(String id) {
        GovPolicyDO row = mapper.findById(id);
        if (row == null) {
            return 0;
        }
        row.setStatus("DISABLED");
        return mapper.updateById(row);
    }

    public int updateMode(String id, String mode) {
        GovPolicyDO row = mapper.findById(id);
        if (row == null) {
            return 0;
        }
        row.setMode(mode);
        return mapper.updateById(row);
    }
}
