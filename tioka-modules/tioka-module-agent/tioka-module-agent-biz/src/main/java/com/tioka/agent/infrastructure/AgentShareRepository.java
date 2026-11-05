package com.tioka.agent.infrastructure;
import com.tioka.agent.infrastructure.mapper.AgentShareMapper;
import com.tioka.agent.infrastructure.persistence.AgentShareDO;
import org.springframework.stereotype.Repository;
@Repository
public class AgentShareRepository {
    private final AgentShareMapper mapper;
    public AgentShareRepository(AgentShareMapper mapper) { this.mapper = mapper; }
    public void insert(AgentShareDO row) { mapper.insert(row); }
}
