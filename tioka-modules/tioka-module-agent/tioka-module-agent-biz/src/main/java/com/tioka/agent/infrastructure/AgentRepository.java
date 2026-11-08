package com.tioka.agent.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    public AgentDO findAgent(String id) {
        return agents.selectById(id);
    }

    public List<AgentDO> listAgents() {
        return agents.selectList(null);
    }

    /** 仅插入；已发布版本不可变，不提供 update。 */
    public void insertVersion(AgentVersionDO row) {
        versions.insert(row);
    }

    public AgentVersionDO findVersion(String id) {
        return versions.selectById(id);
    }

    public List<AgentVersionDO> listVersions(String agentId) {
        return versions.selectList(new QueryWrapper<AgentVersionDO>()
                .eq("agent_id", agentId)
                .orderByAsc("version_no"));
    }

    public int maxVersionNo(String agentId) {
        AgentVersionDO row = versions.selectOne(new QueryWrapper<AgentVersionDO>()
                .eq("agent_id", agentId)
                .orderByDesc("version_no")
                .last("LIMIT 1"));
        return row == null || row.getVersionNo() == null ? 0 : row.getVersionNo();
    }

    /** 仅允许 RETIRED 状态更新（ArchUnit agentVersionImmutable 例外）。 */
    public void retire(String versionId) {
        AgentVersionDO existing = versions.selectById(versionId);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "version 不存在");
        }
        existing.setStatus("RETIRED");
        versions.updateById(existing);
    }
}
