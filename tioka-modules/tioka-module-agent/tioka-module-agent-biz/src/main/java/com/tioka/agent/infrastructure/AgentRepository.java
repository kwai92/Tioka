package com.tioka.agent.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tioka.agent.infrastructure.mapper.AgentMapper;
import com.tioka.agent.infrastructure.mapper.AgentVersionMapper;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    // ---- TRU-03：授权书生命周期（charter_status / reviewed_at / owner_id，V201）----

    /** 指定用户作为负责人的全部 Agent（跨工作空间；调用方须以 SYSTEM 身份绕过行级过滤）。 */
    public List<AgentDO> listByOwner(String ownerId) {
        return agents.selectList(new QueryWrapper<AgentDO>().eq("owner_id", ownerId));
    }

    /** 指定用户在某工作空间内作为负责人的 Agent。 */
    public List<AgentDO> listByOwnerInWorkspace(String workspaceId, String ownerId) {
        return agents.selectList(new QueryWrapper<AgentDO>()
                .eq("workspace_id", workspaceId)
                .eq("owner_id", ownerId));
    }

    /** ACTIVE → SUSPENDED（CAS）；已是 SUSPENDED / HANDOVER 时影响 0 行（重复事件幂等）。 */
    public boolean suspendCharter(String agentId) {
        AgentDO patch = new AgentDO();
        patch.setCharterStatus(CharterStatusValues.SUSPENDED);
        return agents.update(patch, new QueryWrapper<AgentDO>()
                .eq("id", agentId)
                .eq("charter_status", CharterStatusValues.ACTIVE)) > 0;
    }

    /** ACTIVE / SUSPENDED → HANDOVER 并更换负责人；无可转移状态影响 0 行。 */
    public boolean handoverCharter(String agentId, String newOwnerId) {
        AgentDO patch = new AgentDO();
        patch.setCharterStatus(CharterStatusValues.HANDOVER);
        patch.setOwnerId(newOwnerId);
        return agents.update(patch, new QueryWrapper<AgentDO>()
                .eq("id", agentId)
                .in("charter_status", CharterStatusValues.ACTIVE, CharterStatusValues.SUSPENDED)) > 0;
    }

    /** HANDOVER → ACTIVE 并刷新 reviewed_at（新负责人确认授权书）；非 HANDOVER 影响 0 行。 */
    public boolean activateCharter(String agentId) {
        AgentDO patch = new AgentDO();
        patch.setCharterStatus(CharterStatusValues.ACTIVE);
        patch.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        return agents.update(patch, new QueryWrapper<AgentDO>()
                .eq("id", agentId)
                .eq("charter_status", CharterStatusValues.HANDOVER)) > 0;
    }

    /** charter_status 字面量常量（TRU-03 状态机；与 governance-api CharterStatus 对齐）。 */
    static final class CharterStatusValues {
        static final String ACTIVE = "ACTIVE";
        static final String SUSPENDED = "SUSPENDED";
        static final String HANDOVER = "HANDOVER";

        private CharterStatusValues() {}
    }
}
