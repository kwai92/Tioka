package com.tioka.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tioka.agent.api.AgentPublishedEvent;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.AgentSpecJson;
import com.tioka.agent.domain.AgentStore;
import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

@Service
public class AgentSnapshotService implements AgentSnapshotApi {
    private static final Set<String> READABLE = Set.of("PUBLISHED", "RETIRED");

    private final AgentRepository repo;
    private final AgentStore agents;
    private final ObjectMapper mapper;
    private final Cache<String, AgentSnapshot> byVersion =
            Caffeine.newBuilder().maximumSize(1000).build();
    private final Cache<String, AgentSnapshot> currentPublished =
            Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public AgentSnapshotService(AgentRepository repo, AgentStore agents, ObjectMapper mapper) {
        this.repo = repo;
        this.agents = agents;
        this.mapper = mapper;
    }

    @Override
    public AgentSnapshot get(String agentVersionId) {
        if (agentVersionId == null || agentVersionId.isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "agentVersion 不存在");
        }
        AgentSnapshot cached = byVersion.getIfPresent(agentVersionId);
        if (cached != null) {
            return cached;
        }
        AgentVersionDO row = repo.findVersion(agentVersionId);
        if (row == null || row.getStatus() == null || !READABLE.contains(row.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "agentVersion 不存在");
        }
        AgentSnapshot snap = toSnapshot(row);
        byVersion.put(agentVersionId, snap);
        return snap;
    }

    @Override
    public AgentSnapshot currentPublished(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "agent 未发布");
        }
        AgentSnapshot cached = currentPublished.getIfPresent(agentId);
        if (cached != null) {
            return cached;
        }
        AgentStore.Agent agent = agents.getAgent(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "agent 不存在"));
        if (agent.publishedVersionId() == null || agent.publishedVersionId().isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "agent 未发布");
        }
        AgentSnapshot snap = get(agent.publishedVersionId());
        currentPublished.put(agentId, snap);
        return snap;
    }

    @EventListener
    public void onPublished(AgentPublishedEvent event) {
        if (event == null) return;
        currentPublished.invalidate(event.agentId());
    }

    private AgentSnapshot toSnapshot(AgentVersionDO row) {
        AgentStore.Agent agent = agents.getAgent(row.getAgentId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "agent 不存在"));
        AgentSpec spec = AgentSpecJson.parse(mapper, row.getSpecJson());
        String owner = agent.ownerId() == null ? row.getCreateBy() : agent.ownerId();
        return new AgentSnapshot(
                row.getId(),
                row.getAgentId(),
                row.getVersionNo() == null ? 0 : row.getVersionNo(),
                agent.workspaceId(),
                owner,
                row.getSnapshotHash(),
                spec);
    }
}
