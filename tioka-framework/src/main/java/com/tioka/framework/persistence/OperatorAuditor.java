package com.tioka.framework.persistence;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.TiokaAuthentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class OperatorAuditor {
    private OperatorAuditor() {}

    public static String currentOperator() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof TiokaAuthentication ta) {
            Principal p = ta.getPrincipal();
            return p == null || p.userId() == null ? "system" : p.userId();
        }
        return "system";
    }
}
