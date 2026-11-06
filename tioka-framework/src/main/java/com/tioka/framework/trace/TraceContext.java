package com.tioka.framework.trace;

import com.github.f4b6a3.ulid.UlidCreator;

public final class TraceContext {
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private TraceContext() {}

    public static void setTraceId(String traceId) { TRACE.set(traceId); }

    public static String getTraceId() {
        String id = TRACE.get();
        if (id == null) {
            id = UlidCreator.getMonotonicUlid().toString();
            TRACE.set(id);
        }
        return id;
    }

    public static void clear() { TRACE.remove(); }
}
