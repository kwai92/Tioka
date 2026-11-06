package com.tioka.agent.application;

import com.tioka.framework.id.Ids;

import com.tioka.agent.api.AgentQueryApi;
import com.tioka.agent.domain.AgentStore;
import com.tioka.agent.domain.AgentStore.Agent;
import com.tioka.agent.domain.AgentStore.AgentVersion;
import com.tioka.agent.domain.AgentStore.SkillBinding;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.RoutingPlanQueryApi;
import com.tioka.skill.api.SkillQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class AgentService implements AgentQueryApi {
    private final RoutingPlanQueryApi routingPlanQueryApi;
    private final SkillQueryApi skillQueryApi;
    private final AgentStore store;

    public AgentService(RoutingPlanQueryApi routingPlanQueryApi, SkillQueryApi skillQueryApi, AgentStore store) {
        this.routingPlanQueryApi = routingPlanQueryApi;
        this.skillQueryApi = skillQueryApi;
        this.store = store;
    }

    public Agent create(String name, String workspaceId) {
        String id = Ids.newId("agt");
        Agent a = new Agent(id, name, workspaceId, false, null);
        store.putAgent(a);
        return a;
    }

    public AgentVersion draft(String agentId, String instructions, String routingPlanId,
                              List<String> mcpGrantIds, List<SkillBinding> skillBindings) {
        if (store.getAgent(agentId).isEmpty()) throw new BizException(ErrorCode.NOT_FOUND, "agent 不存在");
        String id = Ids.newId("av");
        AgentVersion v = new AgentVersion(id, agentId, instructions, routingPlanId,
                mcpGrantIds == null ? List.of() : mcpGrantIds,
                skillBindings == null ? List.of() : skillBindings,
                null, false);
        store.putVersion(v);
        return v;
    }

    /** 兼容旧调用。 */
    public AgentVersion draft(String agentId, String instructions, String routingPlanId, List<String> mcpGrantIds) {
        return draft(agentId, instructions, routingPlanId, mcpGrantIds, List.of());
    }

    @Transactional
    public AgentVersion publish(String versionId) {
        AgentVersion v = store.getVersion(versionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "version 不存在"));
        if (v.published()) {
            throw new BizException(ErrorCode.CONFLICT, "已发布版本不可变: " + versionId);
        }
        if (!routingPlanQueryApi.isPublished(v.routingPlanId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "启用前必须绑定已发布 RoutingPlan");
        }
        String hash = sha256(v.agentId() + ":" + v.routingPlanId() + ":" + v.instructions()
                + ":" + v.mcpGrantIds() + ":" + v.skillBindings());
        AgentVersion published = new AgentVersion(v.id(), v.agentId(), v.instructions(), v.routingPlanId(),
                v.mcpGrantIds(), v.skillBindings(), hash, true);
        store.putVersion(published);
        Agent a = store.getAgent(v.agentId()).orElseThrow();
        store.putAgent(new Agent(a.id(), a.name(), a.workspaceId(), true, published.id()));
        return published;
    }

    @Override
    public AgentVersionView requirePublished(String versionId) {
        AgentVersion v = store.getVersion(versionId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "AgentVersion 未发布"));
        if (!v.published()) throw new BizException(ErrorCode.BAD_REQUEST, "AgentVersion 未发布");
        List<SkillBindingView> skills = new ArrayList<>();
        for (SkillBinding b : v.skillBindings()) {
            skillQueryApi.findPublished(b.skillId()).ifPresent(sk ->
                    skills.add(new SkillBindingView(sk.id(), sk.name(),
                            b.mode() == null ? "auto" : b.mode(), sk.description(), sk.skillMd())));
        }
        return new AgentVersionView(v.id(), v.agentId(), v.instructions(), v.routingPlanId(),
                v.mcpGrantIds(), skills);
    }

    public List<Agent> list() {
        return store.agents();
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
