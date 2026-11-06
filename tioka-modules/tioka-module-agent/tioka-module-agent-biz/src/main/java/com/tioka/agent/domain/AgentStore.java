package com.tioka.agent.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AgentStore {
    private final AgentRepository repo;
    private final ObjectMapper mapper;

    public AgentStore(AgentRepository repo, ObjectMapper mapper) {
        this.mapper = mapper;
        this.repo = repo;
    }

    public void putAgent(Agent a) {
        AgentDO row = new AgentDO();
        row.setId(a.id());
        row.setName(a.name());
        row.setWorkspaceId(a.workspaceId());
        row.setEnabled(a.enabled());
        row.setPublishedVersionId(a.publishedVersionId());
        repo.upsertAgent(row);
    }

    public Optional<Agent> getAgent(String id) {
        AgentDO row = repo.findAgent(id);
        return row == null ? Optional.empty()
                : Optional.of(new Agent(row.getId(), row.getName(), row.getWorkspaceId(),
                Boolean.TRUE.equals(row.getEnabled()), row.getPublishedVersionId()));
    }

    public List<Agent> agents() {
        return repo.listAgents().stream()
                .map(r -> new Agent(r.getId(), r.getName(), r.getWorkspaceId(),
                        Boolean.TRUE.equals(r.getEnabled()), r.getPublishedVersionId()))
                .toList();
    }

    public void putVersion(AgentVersion v) {
        AgentVersionDO row = new AgentVersionDO();
        row.setId(v.id());
        row.setAgentId(v.agentId());
        row.setInstructions(v.instructions());
        row.setRoutingPlanId(v.routingPlanId());
        row.setMcpGrantIds(toJson(v.mcpGrantIds()));
        row.setSkillBindings(toSkillJson(v.skillBindings()));
        row.setSnapshotHash(v.snapshotHash());
        row.setPublished(v.published());
        repo.upsertVersion(row);
    }

    public Optional<AgentVersion> getVersion(String id) {
        AgentVersionDO row = repo.findVersion(id);
        if (row == null) return Optional.empty();
        return Optional.of(new AgentVersion(
                row.getId(), row.getAgentId(), row.getInstructions(), row.getRoutingPlanId(),
                fromJson(row.getMcpGrantIds()), fromSkillJson(row.getSkillBindings()),
                row.getSnapshotHash(), Boolean.TRUE.equals(row.getPublished())));
    }

    private String toJson(List<String> ids) {
        try {
            return mapper.writeValueAsString(ids == null ? List.of() : ids);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "JSON 解析失败");
        }
    }

    private String toSkillJson(List<SkillBinding> bindings) {
        try {
            return mapper.writeValueAsString(bindings == null ? List.of() : bindings);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<SkillBinding> fromSkillJson(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "JSON 解析失败");
        }
    }

    public record Agent(String id, String name, String workspaceId, boolean enabled, String publishedVersionId) {}

    public record SkillBinding(String skillId, String mode) {}

    public record AgentVersion(String id, String agentId, String instructions, String routingPlanId,
                               List<String> mcpGrantIds, List<SkillBinding> skillBindings,
                               String snapshotHash, boolean published) {
        /** 发布快照中的 skillIds（由 skillBindings 投影）。 */
        public List<String> skillIds() {
            if (skillBindings == null || skillBindings.isEmpty()) return List.of();
            return skillBindings.stream().map(SkillBinding::skillId).toList();
        }
    }
}
