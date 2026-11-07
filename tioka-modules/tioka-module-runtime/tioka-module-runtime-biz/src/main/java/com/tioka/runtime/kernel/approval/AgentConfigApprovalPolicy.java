package com.tioka.runtime.kernel.approval;

import com.tioka.runtime.api.approval.AgentApprovalSettingsLookup;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.approval.ToolRisk;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 默认审批策略：读 AgentSpec.approval.toolPolicies；未配置按风险默认（M1-02 RT-07）。
 */
@Component
public class AgentConfigApprovalPolicy implements ApprovalPolicy {
    public static final String CODE = "agent-config";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final AgentApprovalSettingsLookup settingsLookup;

    public AgentConfigApprovalPolicy(Optional<AgentApprovalSettingsLookup> settingsLookup) {
        this.settingsLookup = settingsLookup.orElse(agentVersionId -> Optional.empty());
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Decision evaluate(ApprovalRequestContext ctx) {
        String policy = resolvePolicy(ctx);
        return switch (policy) {
            case "auto" -> new Allow();
            case "deny" -> new Deny("策略拒绝：toolPolicies=deny");
            default -> new RequireApproval(
                    new Assignee(null, null),
                    DEFAULT_TTL,
                    "工具 " + ctx.toolName() + " 风险 " + ctx.risk() + " 需审批");
        };
    }

    private String resolvePolicy(ApprovalRequestContext ctx) {
        Map<String, String> policies = settingsLookup.toolPolicies(ctx.agentVersionId()).orElse(Map.of());
        String named = policies.get(ctx.toolName());
        if (named != null && !named.isBlank()) {
            return named.trim().toLowerCase(Locale.ROOT);
        }
        return ctx.risk() == ToolRisk.READ ? "auto" : "approve";
    }
}
