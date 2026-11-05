package com.tioka.framework.error;

public enum ErrorCode {
    OK(0, "成功"),
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或令牌无效"),
    FORBIDDEN(40300, "无权限"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "资源冲突"),
    RATE_LIMITED(42900, "超出配额"),
    INTERNAL(50000, "内部错误"),
    DEPENDENCY(50200, "下游依赖失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
