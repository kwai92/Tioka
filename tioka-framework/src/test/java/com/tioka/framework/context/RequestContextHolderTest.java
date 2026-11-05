package com.tioka.framework.context;

import com.tioka.framework.tenant.TenantContext;
import com.tioka.framework.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextHolderTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.end();
    }

    @Test
    void injectsTenantAndTrace() {
        RequestContextHolder.begin("trace-abc", 1L, 2L, 3L);
        assertEquals("trace-abc", TraceContext.getTraceId());
        assertEquals(1L, TenantContext.getTenantId());
        assertEquals(2L, TenantContext.getUserId());
        assertEquals(3L, TenantContext.getWorkspaceId());
    }
}
