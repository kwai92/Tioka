package com.tioka.framework.error;

public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = ErrorCode.OK.getCode();
        r.message = ErrorCode.OK.getMessage();
        r.data = data;
        r.traceId = com.tioka.framework.trace.TraceContext.getTraceId();
        return r;
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = errorCode.getCode();
        r.message = message == null ? errorCode.getMessage() : message;
        r.traceId = com.tioka.framework.trace.TraceContext.getTraceId();
        return r;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public String getTraceId() { return traceId; }
}
