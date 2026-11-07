package com.tioka.runtime.kernel.approval;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.approval.ApprovalRequestContexts;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ToolProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigApprovalPolicyTest {

    private final Principal user = new Principal("1", "ws", PrincipalType.USER,
            Set.of(), Set.of("*"), "password");

    @Test
    void readAutoWriteApproveByDefault() {
        AgentConfigApprovalPolicy p = new AgentConfigApprovalPolicy(Optional.empty());
        assertInstanceOf(ApprovalPolicy.Allow.class, p.evaluate(ctx("echo", ToolRisk.READ)));
        assertInstanceOf(ApprovalPolicy.RequireApproval.class, p.evaluate(ctx("upd", ToolRisk.WRITE)));
        assertInstanceOf(ApprovalPolicy.RequireApproval.class, p.evaluate(ctx("rm", ToolRisk.DESTRUCTIVE)));
    }

    @Test
    void toolPoliciesOverride() {
        AgentConfigApprovalPolicy p = new AgentConfigApprovalPolicy(Optional.of(
                id -> Optional.of(Map.of("echo", "deny", "upd", "auto"))));
        assertInstanceOf(ApprovalPolicy.Deny.class, p.evaluate(ctx("echo", ToolRisk.READ)));
        assertInstanceOf(ApprovalPolicy.Allow.class, p.evaluate(ctx("upd", ToolRisk.WRITE)));
    }

    private ApprovalPolicy.ApprovalRequestContext ctx(String tool, ToolRisk risk) {
        return ApprovalRequestContexts.from(user, "av_1", tool, Map.of(),
                new ToolProvider.ToolDescriptor(tool, "", Map.of(), risk), Map.of());
    }
}
