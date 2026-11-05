package com.tioka.agent.application;

import com.tioka.agent.infrastructure.AgentSandboxRepository;
import com.tioka.agent.infrastructure.persistence.AgentSandboxDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchApi;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SandboxPolicyService {
    public enum Level { STRICT, STANDARD, PERMISSIVE }
    private final AgentSandboxRepository sandboxes;
    private final OrchApi orchApi;

    public SandboxPolicyService(AgentSandboxRepository sandboxes, OrchApi orchApi) {
        this.sandboxes = sandboxes;
        this.orchApi = orchApi;
    }

    public void setLevel(String agentId, Level level) {
        AgentSandboxDO row = new AgentSandboxDO();
        row.setAgentId(agentId);
        row.setLevel(level.name());
        sandboxes.upsert(row);
    }

    public void assertAllowed(String agentId, String action) {
        AgentSandboxDO row = sandboxes.find(agentId);
        Level level = row == null ? Level.STANDARD : Level.valueOf(row.getLevel());
        if (level == Level.STRICT && action.startsWith("net:")) {
            String runId = orchApi.ingestEvent("sandbox.escalate", null, Map.of("agentId", agentId, "action", action));
            orchApi.openAttention(runId, "沙箱越权审批", Map.of("action", action));
            throw new BizException(ErrorCode.FORBIDDEN, "STRICT 策略拒绝，已升级审批");
        }
    }
}
