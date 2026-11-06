package com.tioka.agent.application;

import com.tioka.framework.id.Ids;

import com.tioka.agent.infrastructure.AgentShareRepository;
import com.tioka.agent.infrastructure.persistence.AgentShareDO;
import org.springframework.stereotype.Service;


@Service
public class AgentShareService {
    private final AgentShareRepository shares;
    public AgentShareService(AgentShareRepository shares) { this.shares = shares; }

    public Share share(String agentId, String mode) {
        Share s = new Share(Ids.newId("sh"), agentId, mode == null ? "READONLY" : mode);
        AgentShareDO row = new AgentShareDO();
        row.setId(s.id()); row.setAgentId(s.agentId()); row.setMode(s.mode());
        shares.insert(row);
        return s;
    }
    public record Share(String id, String agentId, String mode) {}
}
