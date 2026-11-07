package com.tioka.runtime.kernel.approval;

import com.tioka.runtime.api.approval.ApprovalPolicy;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 所有工具均需审批（TurnOptions.approvalPolicy=always）。 */
@Component
public class AlwaysApprovalPolicy implements ApprovalPolicy {
    public static final String CODE = "always";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Decision evaluate(ApprovalRequestContext ctx) {
        return new RequireApproval(
                new Assignee(null, null),
                Duration.ofHours(24),
                "策略 always：工具 " + ctx.toolName() + " 需审批");
    }
}
