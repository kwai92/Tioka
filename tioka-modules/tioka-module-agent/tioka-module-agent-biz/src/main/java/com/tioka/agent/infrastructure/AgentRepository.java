package com.tioka.agent.infrastructure;

import com.tioka.agent.infrastructure.mapper.AgentMapper;
import com.tioka.agent.infrastructure.mapper.AgentVersionMapper;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AgentRepository {
    private final AgentMapper agents;
    private final AgentVersionMapper versions;

    public AgentRepository(AgentMapper agents, AgentVersionMapper versions) {
        this.agents = agents;
        this.versions = versions;
    }

    public void upsertAgent(AgentDO row) {
        AgentDO existing = agents.selectById(row.getId());
        if (existing == null) {
            if (row.getVersion() == null) {
                row.setVersion(0);
            }
            agents.insert(row);
            return;
        }
        if (row.getVersion() == null) {
            row.setVersion(existing.getVersion());
        }
        int n = agents.updateById(row);
        if (n == 0) {
            throw new BizException(ErrorCode.STALE_REVISION, "Agent 已被并发修改: " + row.getId());
        }
    }

    public AgentDO findAgent(String id) { return agents.selectById(id); }

    public List<AgentDO> listAgents() { return agents.selectList(null); }

    public void upsertVersion(AgentVersionDO row) {
        AgentVersionDO existing = versions.selectById(row.getId());
        if (existing == null) {
            versions.insert(row);
            return;
        }
        if (Boolean.TRUE.equals(existing.getPublished())) {
            throw new BizException(ErrorCode.CONFLICT, "已发布版本不可变: " + row.getId());
        }
        versions.updateById(row);
    }

    public AgentVersionDO findVersion(String id) { return versions.selectById(id); }
}
