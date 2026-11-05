package com.tioka.framework.persistence;

import com.tioka.framework.tenant.TenantContext;

public final class OperatorAuditor {
    private OperatorAuditor() {}

    public static String currentOperator() {
        Long userId = TenantContext.getUserId();
        return userId == null ? "system" : String.valueOf(userId);
    }
}
