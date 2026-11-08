package com.tioka.framework.context;

import com.tioka.framework.tenant.TenantContext;
import com.tioka.framework.trace.TraceContext;

/**
 * 统一请求上下文入口：Trace + 租户遗留上下文 + 关联上下文（ADR-073）。
 */
public final class RequestContextHolder {
    private static final ThreadLocal<RequestContext> CORRELATION = new ThreadLocal<>();

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
        CORRELATION.remove();
    }

    public static void setCorrelation(RequestContext ctx) {
        if (ctx == null) {
            CORRELATION.remove();
        } else {
            CORRELATION.set(ctx);
        }
    }

    public static RequestContext getCorrelation() {
        RequestContext ctx = CORRELATION.get();
        return ctx == null ? RequestContext.empty() : ctx;
    }

    public static void clearCorrelation() {
        CORRELATION.remove();
    }

    /** 跨线程传递：复制当前关联上下文与 Trace，供线程池 / 异步任务使用。 */
    public static Runnable wrap(Runnable task) {
        RequestContext captured = getCorrelation();
        String traceId = TraceContext.getTraceId();
        return () -> {
            String previousTrace = null;
            try {
                previousTrace = TraceContext.getTraceId();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                if (traceId != null) {
                    TraceContext.setTraceId(traceId);
                }
                setCorrelation(captured);
                task.run();
            } finally {
                clearCorrelation();
                if (previousTrace != null) {
                    TraceContext.setTraceId(previousTrace);
                } else {
                    TraceContext.clear();
                }
            }
        };
    }
}
