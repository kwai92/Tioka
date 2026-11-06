package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.system.api.spi.EgressGuard;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AST-02 #50：toolPolicies 键 ∈ 本 spec 工具集合；networkAllowlist 主机格式。回滚跳过。
 */
@Component
@Order(50)
public class PolicyPublishCheck implements PublishCheck {
    private final EgressGuard egressGuard;

    public PolicyPublishCheck(EgressGuard egressGuard) {
        this.egressGuard = egressGuard;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        if (ctx.rollback()) {
            return List.of();
        }
        List<PublishError> errors = new ArrayList<>();
        Set<String> toolNames = collectToolNames(spec);
        Map<String, String> policies = spec.approval() == null
                ? Map.of() : spec.approval().toolPolicies();
        for (String key : policies.keySet()) {
            if (!toolNames.contains(key)) {
                errors.add(new PublishError("/approval/toolPolicies/" + key, "POLICY_UNKNOWN_TOOL",
                        "toolPolicies 键不在本 Agent 工具集合中: " + key));
            }
        }

        List<String> hosts = spec.sandbox() == null ? List.of() : spec.sandbox().networkAllowlist();
        for (int i = 0; i < hosts.size(); i++) {
            String host = hosts.get(i);
            if (!egressGuard.isValidHostPattern(host)) {
                errors.add(new PublishError("/sandbox/networkAllowlist/" + i, "HOST_INVALID",
                        "主机格式非法: " + host));
            }
        }
        return errors;
    }

    static Set<String> collectToolNames(AgentSpec spec) {
        Set<String> names = new HashSet<>();
        if (spec.tools() != null) {
            names.addAll(spec.tools().builtin());
            for (AgentSpec.ToolSet.McpBinding b : spec.tools().mcp()) {
                names.addAll(b.allowlist());
            }
        }
        return names;
    }
}
