package com.tioka.framework.persistence;

import com.tioka.framework.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperatorAuditorTest {
    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void fallsBackToSystemWhenNoUser() {
        assertEquals("system", OperatorAuditor.currentOperator());
    }

    @Test
    void usesUserIdWhenPresent() {
        TenantContext.set(null, 42L, null);
        assertEquals("42", OperatorAuditor.currentOperator());
    }
}
