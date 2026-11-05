package com.tioka.framework.trace;

import java.util.UUID;

public final class TraceContext {
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private TraceContext() {}

    public static void setTraceId(String traceId) { TRACE.set(traceId); }

    public static String getTraceId() {
        String id = TRACE.get();
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "");
            TRACE.set(id);
        }
        return id;
    }

    public static void clear() { TRACE.remove(); }
}
