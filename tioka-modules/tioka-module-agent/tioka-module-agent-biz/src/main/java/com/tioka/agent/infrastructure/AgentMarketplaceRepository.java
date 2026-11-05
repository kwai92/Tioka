package com.tioka.agent.infrastructure;
import com.tioka.agent.infrastructure.mapper.AgentMarketplaceMapper;
import com.tioka.agent.infrastructure.persistence.AgentMarketplaceDO;
import org.springframework.stereotype.Repository;
@Repository
public class AgentMarketplaceRepository {
    private final AgentMarketplaceMapper mapper;
    public AgentMarketplaceRepository(AgentMarketplaceMapper mapper) { this.mapper = mapper; }
    public void insert(AgentMarketplaceDO row) { mapper.insert(row); }
    public AgentMarketplaceDO findById(String id) { return mapper.selectById(id); }
    public void update(AgentMarketplaceDO row) { mapper.updateById(row); }
}
