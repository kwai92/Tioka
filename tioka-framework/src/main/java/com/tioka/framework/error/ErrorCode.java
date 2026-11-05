package com.tioka.framework.error;

/**
 * 全局错误码与 HTTP 状态映射（ADR-029 第 14、18 条）。新增错误码只能在这里登记。
 */
public enum ErrorCode {
    OK(0, 200, "成功"),
    BAD_REQUEST(40000, 400, "请求参数错误"),
    /** 未登录或令牌无效，HTTP 401。 */
    UNAUTHORIZED(40100, 401, "未登录或令牌无效"),
    FORBIDDEN(40300, 403, "无权限"),
    NOT_FOUND(40400, 404, "资源不存在"),
    CONFLICT(40900, 409, "资源冲突"),
    /** 乐观锁版本已过期，HTTP 409。 */
    STALE_REVISION(40901, 409, "数据已被修改，请刷新后重试"),
    RATE_LIMITED(42900, 429, "请求过于频繁"),
    /** 配额用尽（ADR-017、ADR-029 第 7 条），HTTP 429。 */
    QUOTA_EXCEEDED(42901, 429, "配额已用尽"),
    INTERNAL(50000, 500, "内部错误"),
    /** 会话锁租约丢失，仅内部使用，不对外返回（ADR-029 第 4 条）。 */
    LEASE_LOST(50001, 500, "执行租约已失效"),
    /** M0 下线的假实现 API，HTTP 501。 */
    NOT_IMPLEMENTED(50100, 501, "功能尚未实现"),
    DEPENDENCY(50200, 502, "下游依赖失败");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public String getMessage() { return message; }
}
