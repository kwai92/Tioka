package com.tioka.runtime.kernel.approval;

import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import org.springframework.stereotype.Component;

/**
 * 永不审批（仅 SYSTEM 主体可用，M1-02）。
 * 非 SYSTEM 调用时拒绝该策略，由 {@link ApprovalPolicyResolver} 在解析阶段拦截。
 */
@Component
public class NeverApprovalPolicy implements ApprovalPolicy {
    public static final String CODE = "never";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Decision evaluate(ApprovalRequestContext ctx) {
        if (ctx.principal() == null || ctx.principal().type() != PrincipalType.SYSTEM) {
            return new Deny("never 策略仅限 SYSTEM 主体");
        }
        return new Allow();
    }
}
