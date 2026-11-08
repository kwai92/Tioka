package com.tioka.runtime.api.pipeline;

/**
 * 工具调用管线步骤（P3-00b / K1b 注册点）。
 * 每个实现一个固定 {@link #order()}；同序号只允许一个实现。
 * 序号见 {@link ToolPipelineOrders}（M3-05 第 0 节）。
 *
 * <p>{@code DefaultToolGateway} 在 PK-24b 合入后改为按序号依次调用已注册步骤；
 * 本接口先行落地，接线不改 Gateway 行为直至持有 K1b。
 */
public interface ToolPipelineStep {

    /** 管线序号（可含小数，如 2.5）。 */
    double order();

    /** 步骤短名（日志 / 审计）。 */
    String name();

    /**
     * 执行本步。返回 {@link ToolPipelineVerdict#CONTINUE} 进入下一步；
     * {@link ToolPipelineVerdict#DENY} / {@link ToolPipelineVerdict#ESCALATE} 终止后续步骤。
     */
    ToolPipelineVerdict execute(ToolPipelineContext ctx);
}
