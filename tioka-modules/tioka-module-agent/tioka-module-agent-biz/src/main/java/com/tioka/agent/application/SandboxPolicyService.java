package com.tioka.agent.application;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchAttentionApi;
import com.tioka.orchestration.api.engine.OrchApi;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 沙箱策略改读 Agent 快照（AST-01；agent_sandbox 表已由 V125 删除）。
 */
@Service
public class SandboxPolicyService {
    private final AgentSnapshotApi snapshots;
    private final AgentService agents;
    private final OrchApi orchEngine;
    private final OrchAttentionApi orchAttention;

    public SandboxPolicyService(AgentSnapshotApi snapshots,
                                AgentService agents,
                                OrchApi orchEngine,
                                OrchAttentionApi orchAttention) {
        this.snapshots = snapshots;
        this.agents = agents;
        this.orchEngine = orchEngine;
        this.orchAttention = orchAttention;
    }

    public void setLevel(String agentId, String level) {
        AgentService.DraftView draft = agents.getDraft(agentId);
        AgentSpec spec = draft.spec();
        AgentSpec updated = new AgentSpec(
                spec.schemaVersion(),
                spec.profile(),
                spec.instructions(),
                spec.model(),
                spec.tools(),
                spec.skills(),
                spec.knowledge(),
                new AgentSpec.SandboxPolicy(level, spec.sandbox().networkAllowlist()),
                spec.approval(),
                spec.limits(),
                spec.charter(),
                spec.output(),
                spec.subAgents(),
                spec.dataClearance());
        agents.saveDraft(agentId, updated, draft.revision());
    }

    public void assertAllowed(String agentId, String action) {
        String level = "EDIT";
        try {
            level = snapshots.currentPublished(agentId).spec().sandbox().level();
        } catch (BizException ignored) {
            try {
                level = agents.getDraft(agentId).spec().sandbox().level();
            } catch (BizException ignored2) {
                // 默认 EDIT
            }
        }
        if ("READ".equals(level) && action.startsWith("net:")) {
            // 过渡桥：EventAck.eventId 对 STORED 即为 legacy runId（见 OrchEngineService）
            OrchApi.EventAck ack = orchEngine.ingest(new OrchApi.EventEnvelope(
                    "sandbox",
                    "sandbox.escalate",
                    "sandbox-" + agentId + "-" + System.currentTimeMillis(),
                    agentId,
                    Map.of(),
                    Map.of("agentId", agentId, "action", action),
                    0));
            orchAttention.openAttention(ack.eventId(), "沙箱越权审批", Map.of("action", action));
            throw new BizException(ErrorCode.FORBIDDEN, "READ 策略拒绝，已升级审批");
        }
    }
}
