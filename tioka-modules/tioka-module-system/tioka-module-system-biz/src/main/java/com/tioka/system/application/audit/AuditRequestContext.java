package com.tioka.system.application.audit;

import com.tioka.framework.id.Ids;
import org.slf4j.MDC;

/**
 * 审计关联上下文（OBS-01）：trace / request 缺则生成并放入 MDC。
 */
public final class AuditRequestContext {
    public static final String MDC_TRACE = "traceId";
    public static final String MDC_REQUEST = "requestId";

    private AuditRequestContext() {}

    public static String currentTraceId() {
        String v = MDC.get(MDC_TRACE);
        if (v == null || v.isBlank()) {
            v = Ids.newId("trc");
            MDC.put(MDC_TRACE, v);
        }
        return v;
    }

    public static String currentRequestId() {
        String v = MDC.get(MDC_REQUEST);
        if (v == null || v.isBlank()) {
            v = Ids.newId("req");
            MDC.put(MDC_REQUEST, v);
        }
        return v;
    }

    public static void clear() {
        MDC.remove(MDC_TRACE);
        MDC.remove(MDC_REQUEST);
    }
}
