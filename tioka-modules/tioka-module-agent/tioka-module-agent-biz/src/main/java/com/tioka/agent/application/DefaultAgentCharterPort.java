package com.tioka.agent.application;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.governance.api.charter.AgentCharterPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * TRU-03：{@link AgentCharterPort} 的 agent 域实现（读写 {@code agent_agent} 授权书状态列，V201）。
 *
 * <p>状态转移全部为 CAS（WHERE charter_status = 期望值），由 governance-biz
 * {@code CharterLifecycleService} 编排状态机与事件；本类只做行级转移并在转移后
 * 失效快照缓存，保证同实例 423 拦截即时生效。
 */
@Service
public class DefaultAgentCharterPort implements AgentCharterPort {

    private final AgentRepository repo;
    private final AgentSnapshotService snapshots;

    public DefaultAgentCharterPort(AgentRepository repo, AgentSnapshotService snapshots) {
        this.repo = repo;
        this.snapshots = snapshots;
    }

    @Override
    public List<CharterAgentView> listOwnedBy(String userId) {
        Objects.requireNonNull(userId, "userId");
        return repo.listByOwner(userId).stream().map(this::toView).toList();
    }

    @Override
    public List<CharterAgentView> listOwnedByInWorkspace(String workspaceId, String userId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        return repo.listByOwnerInWorkspace(workspaceId, userId).stream().map(this::toView).toList();
    }

    @Override
    public String charterStatus(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        AgentDO row = repo.findAgent(agentId);
        return row == null ? null : statusOf(row);
    }

    @Override
    public CharterAgentView getAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        AgentDO row = repo.findAgent(agentId);
        return row == null ? null : toView(row);
    }

    @Override
    public boolean transitionToSuspended(String agentId) {
        boolean changed = repo.suspendCharter(agentId);
        if (changed) {
            snapshots.invalidateCharterCaches();
        }
        return changed;
    }

    @Override
    public boolean transitionToHandover(String agentId, String newOwnerId) {
        Objects.requireNonNull(newOwnerId, "newOwnerId");
        boolean changed = repo.handoverCharter(agentId, newOwnerId);
        if (changed) {
            snapshots.invalidateCharterCaches();
        }
        return changed;
    }

    @Override
    public boolean transitionToActive(String agentId) {
        boolean changed = repo.activateCharter(agentId);
        if (changed) {
            snapshots.invalidateCharterCaches();
        }
        return changed;
    }

    @Override
    public List<String> readableVersionIds(String agentId) {
        return repo.listVersions(agentId).stream()
                .filter(v -> v.getStatus() != null
                        && ("PUBLISHED".equals(v.getStatus()) || "RETIRED".equals(v.getStatus())))
                .map(com.tioka.agent.infrastructure.persistence.AgentVersionDO::getId)
                .toList();
    }

    private static String statusOf(AgentDO row) {
        // V201 列默认 ACTIVE；存量行为 null 时按 ACTIVE 口径（TRU-03 仅非 ACTIVE 才拦截）
        return row.getCharterStatus() == null ? "ACTIVE" : row.getCharterStatus();
    }

    private CharterAgentView toView(AgentDO row) {
        return new CharterAgentView(row.getId(), row.getWorkspaceId(),
                row.getOwnerId(), statusOf(row));
    }
}
