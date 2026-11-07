package com.tioka.runtime.kernel.approval;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按编码选择 {@link ApprovalPolicy}；缺省 {@code agent-config}。 */
@Component
public class ApprovalPolicyResolver {
    private final Map<String, ApprovalPolicy> byCode;

    public ApprovalPolicyResolver(List<ApprovalPolicy> policies) {
        this.byCode = policies.stream()
                .collect(Collectors.toMap(p -> p.code().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
    }

    public ApprovalPolicy resolve(String code, Principal principal) {
        String key = (code == null || code.isBlank())
                ? AgentConfigApprovalPolicy.CODE
                : code.trim().toLowerCase(Locale.ROOT);
        if (NeverApprovalPolicy.CODE.equals(key)
                && (principal == null || principal.type() != PrincipalType.SYSTEM)) {
            throw new BizException(ErrorCode.FORBIDDEN, "never 审批策略仅限 SYSTEM 主体");
        }
        ApprovalPolicy p = byCode.get(key);
        if (p == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知审批策略: " + key);
        }
        return p;
    }
}
