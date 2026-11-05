package com.tioka.agent.infrastructure;
import com.tioka.agent.infrastructure.mapper.AgentSandboxMapper;
import com.tioka.agent.infrastructure.persistence.AgentSandboxDO;
import org.springframework.stereotype.Repository;
@Repository
public class AgentSandboxRepository {
    private final AgentSandboxMapper mapper;
    public AgentSandboxRepository(AgentSandboxMapper mapper) { this.mapper = mapper; }
    public void upsert(AgentSandboxDO row) {
        if (mapper.selectById(row.getAgentId()) == null) mapper.insert(row);
        else mapper.updateById(row);
    }
    public AgentSandboxDO find(String agentId) { return mapper.selectById(agentId); }
}
