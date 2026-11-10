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
    /** 请求头中的空间不是当前用户所属空间（FND-01）。 */
    WORKSPACE_FORBIDDEN(40302, 403, "不是该空间成员"),
    /** 出网守卫拒绝（FND-07）。 */
    EGRESS_DENIED(40303, 403, "目标地址不在出网白名单"),
    NOT_FOUND(40400, 404, "资源不存在"),
    CONFLICT(40900, 409, "资源冲突"),
    /** 乐观锁版本已过期，HTTP 409。 */
    STALE_REVISION(40901, 409, "数据已被修改，请刷新后重试"),
    /** 不能移除空间的最后一个管理员（GOV-01）。 */
    LAST_ADMIN(40902, 409, "空间至少需要一名管理员"),
    /** 委派收窄校验失败：结果不是父授予子集（ADR-039 MAS-01），HTTP 403。 */
    GRANT_NOT_NARROWER(40330, 403, "委派收窄校验失败"),
    /** 委派授予已失效（REAPED / REVOKED / 过期）（ADR-039 MAS-01），HTTP 403。 */
    GRANT_INACTIVE(40331, 403, "委派授予已失效"),
    /** 数据标签超出 binding 所属去向许可，单 binding 剔除事件（DAT-03 / ADR-040），HTTP 403。 */
    DATA_LABEL_EXCEEDED(40332, 403, "数据标签超出模型去向许可"),
    /** 撤销期已过（TRU-07 / ADR-033），HTTP 409。 */
    UNDO_WINDOW_CLOSED(40910, 409, "撤销期已过"),
    /** 策略引擎 DENY（GOV-07 管线第 9 步 / TRU-02 授权书编译），HTTP 403。 */
    POLICY_DENIED(40340, 403, "策略拒绝"),
    /** 策略求值失败且模式为 ENFORCE（GOV-07：ENFORCE→DENY、DRYRUN→放行），HTTP 403。 */
    POLICY_EVAL_FAILED(40341, 403, "策略求值失败"),
    /** Agent 发布校验失败（AST-01/AST-02），HTTP 422。 */
    AGENT_PUBLISH_INVALID(42201, 422, "Agent 发布校验失败"),
    /** Turn 当前数据标签下无获准的模型路由计划（DAT-03 / ADR-040），HTTP 422。 */
    NO_ELIGIBLE_MODEL(42210, 422, "当前对话数据标签下没有获准的模型"),
    /** Agent 已停用，HTTP 423。 */
    AGENT_DISABLED(42300, 423, "Agent 已停用"),
    RATE_LIMITED(42900, 429, "请求过于频繁"),
    /** 配额用尽（ADR-017、ADR-029 第 7 条），HTTP 429。 */
    QUOTA_EXCEEDED(42901, 429, "配额已用尽"),
    /** 供给侧（binding）月度预算耗尽（P2-L5 / GAP-L5-1），HTTP 429。 */
    MODEL_BUDGET_EXHAUSTED(42902, 429, "模型预算已耗尽"),
    INTERNAL(50000, 500, "内部错误"),
    /** 会话锁租约丢失，仅内部使用，不对外返回（ADR-029 第 4 条）。 */
    LEASE_LOST(50001, 500, "执行租约已失效"),
    /** M0 下线的假实现 API，HTTP 501。 */
    NOT_IMPLEMENTED(50100, 501, "功能尚未实现"),
    DEPENDENCY(50200, 502, "下游依赖失败"),
    /** 模型返回不可重试的错误（MH-02）。 */
    MODEL_ERROR(50201, 502, "模型调用失败"),
    /** 故障接力链全部失败（MH-02）。 */
    MODEL_UNAVAILABLE(50301, 503, "模型暂不可用");

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
