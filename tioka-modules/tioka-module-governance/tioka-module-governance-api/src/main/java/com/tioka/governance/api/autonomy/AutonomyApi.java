package com.tioka.governance.api.autonomy;

import java.util.List;

/**
 * 自治等级管理面（ADR-034「接口」节 / TRU-09）。
 *
 * <p>ADR-034 接口中的 {@code decide} 已拆到 {@link AutonomyGate}（ToolGateway 管线第 10 步
 * 只依赖判定 SPI）；本接口承接等级查询与人工管理。实现：governance-biz {@code autonomy} 包。
 *
 * <p>偏差：{@code markIncident} 相对 ADR-034 L131 扩为（agentId, actionClass, actionId, note）——
 * 治理侧不可读 runtime 的 trust_action，由调用方带上归属。登记见 M3-C4 设计笔记。
 *
 * <p>未实现项（M4）：{@link #shadowReport} 影子比对在 TRU-11（M4），M3 返回空报告；
 * 自动晋级（TRU-12）、自动降级消费联动（TRU-10）不在本批。
 */
public interface AutonomyApi {

    /** 每个（Agent，动作类别）的当前等级与上限；未初始化的类别按 L1 缺省返回。 */
    List<AutonomyView> list(String agentId);

    /** 当前等级（0〜4）；无记录返回 L1（1）。授权书上限钳制由调用方（管线第 10 步）执行。 */
    int levelOf(String agentId, String actionClass);

    /**
     * 手动调级：负责人在授权书上限内调高或降级（降级仅 TRU-10 / 本方法内部调用）。
     * 写 {@code trust_autonomy_event} 与审计，发 {@code tioka.trust.autonomy_changed.v1}。
     */
    void setLevel(String agentId, String actionClass, AutonomyLevel level, String reason);

    /** 负责人或管理员标记事故：直接降到 L1（ADR-034「降级」表）。 */
    void markIncident(String agentId, String actionClass, String actionId, String note);

    /** 影子上岗报告（TRU-11 / M4）。M3 返回空报告。 */
    ShadowReport shadowReport(String agentId);

    /**
     * 自动提交动作登记（管线第 10 步 AUTO_COMMIT 后由 commit 路径调用）：
     * 写当日 stats（n+1），L3/L4 按采样比例生成「抽检」记录
     * （{@code trust_autonomy_event.kind=SAMPLING}）。M3 审批台渲染登记，驳回消费归 TRU-10。
     */
    void recordAutoCommit(String agentId, String agentVersionId, String actionClass,
                          int level, String actionId, String workspaceId);

    record AutonomyView(String agentId, String actionClass, int level, String ceiling,
                        java.time.Instant observeUntil) {}

    /** 影子上岗报告（M4 TRU-11）；M3 恒为空。 */
    record ShadowReport(String agentId, int cases, int agreed, String suggestedLevel) {

        public static ShadowReport empty(String agentId) {
            return new ShadowReport(agentId, 0, 0, null);
        }
    }
}
