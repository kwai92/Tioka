package com.tioka.framework.context;

import com.tioka.framework.tenant.TenantContext;
import com.tioka.framework.trace.TraceContext;

/**
 * 统一请求上下文入口：Trace + 租户。
 */
public final class RequestContextHolder {
    private RequestContextHolder() {}

    public static void begin(String traceId, Long tenantId, Long userId, Long workspaceId) {
        if (traceId != null && !traceId.isBlank()) {
            TraceContext.setTraceId(traceId);
        } else {
            TraceContext.getTraceId();
        }
        TenantContext.set(tenantId, userId, workspaceId);
    }

    public static void end() {
        TenantContext.clear();
        TraceContext.clear();
    }
}
