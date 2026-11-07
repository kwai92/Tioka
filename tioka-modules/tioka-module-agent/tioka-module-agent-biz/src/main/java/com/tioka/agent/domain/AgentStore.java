package com.tioka.agent.domain;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AgentStore {
    private final AgentRepository repo;

    public AgentStore(AgentRepository repo) {
        this.repo = repo;
    }

    public void putAgent(Agent a) {
        AgentDO row = new AgentDO();
        row.setId(a.id());
        row.setName(a.name());
        row.setWorkspaceId(a.workspaceId());
        row.setEnabled(a.enabled());
        row.setPublishedVersionId(a.publishedVersionId());
        row.setVisibility(a.visibility() == null ? "WORKSPACE" : a.visibility());
        repo.upsertAgent(row);
    }

    public Optional<Agent> getAgent(String id) {
        AgentDO row = repo.findAgent(id);
        return row == null ? Optional.empty() : Optional.of(toAgent(row));
    }

    public List<Agent> agents() {
        return repo.listAgents().stream().map(this::toAgent).toList();
    }

    private Agent toAgent(AgentDO row) {
        return new Agent(row.getId(), row.getName(), row.getWorkspaceId(),
                Boolean.TRUE.equals(row.getEnabled()), row.getPublishedVersionId(),
                row.getVisibility() == null ? "WORKSPACE" : row.getVisibility(),
                row.getCreateBy());
    }

    public record Agent(String id, String name, String workspaceId, boolean enabled,
                        String publishedVersionId, String visibility, String ownerId) {
        public Agent(String id, String name, String workspaceId, boolean enabled, String publishedVersionId) {
            this(id, name, workspaceId, enabled, publishedVersionId, "WORKSPACE", null);
        }

        public Agent(String id, String name, String workspaceId, boolean enabled,
                     String publishedVersionId, String visibility) {
            this(id, name, workspaceId, enabled, publishedVersionId, visibility, null);
        }
    }
}
