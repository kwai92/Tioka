package com.tioka.agent.application;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchApi;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 沙箱策略改读 Agent 快照（AST-01；agent_sandbox 表已由 V125 删除）。
 */
@Service
public class SandboxPolicyService {
    private final AgentSnapshotApi snapshots;
    private final AgentService agents;
    private final OrchApi orchApi;

    public SandboxPolicyService(AgentSnapshotApi snapshots, AgentService agents, OrchApi orchApi) {
        this.snapshots = snapshots;
        this.agents = agents;
        this.orchApi = orchApi;
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
            String runId = orchApi.ingestEvent("sandbox.escalate", null, Map.of("agentId", agentId, "action", action));
            orchApi.openAttention(runId, "沙箱越权审批", Map.of("action", action));
            throw new BizException(ErrorCode.FORBIDDEN, "READ 策略拒绝，已升级审批");
        }
    }
}
