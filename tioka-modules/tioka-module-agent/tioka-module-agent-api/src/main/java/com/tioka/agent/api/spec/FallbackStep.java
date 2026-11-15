package com.tioka.agent.api.spec;

/**
 * 降级阶梯步骤（ADR-066 §7.1）。
 *
 * <p>{@code OutputContract.fallback} 是有序步骤列表；每一步都受本 Turn 预算与配额约束，
 * 执行足迹写入 {@code data} item 的 {@code producedBy.fallbackSteps}。
 * 列表最后一步必须是 {@link #FALLBACK_VALUE}、{@link #HUMAN} 或 {@link #FAIL} 之一（发布校验检查）。
 */
public enum FallbackStep {

    /** 修复（ADR-066 §6）：按错误类别区别对待，受 {@code maxRepair} 与 {@code maxRepairTokens} 约束。 */
    REPAIR,

    /** 同一模型换更严格的生成方式（如 JSON_OBJECT → STRICT_TOOL）；没有更严格的方式则跳过。 */
    SWITCH_MODE,

    /** 按路由计划的故障接力候选换下一个模型，优先 STRICT_SCHEMA；受 {@code maxModelSwitches} 约束。 */
    SWITCH_MODEL,

    /** 把根对象顶层字段按 {@code x-tioka-group} 分组生成（ADR-066 §7.1）；归 SCH-12，本轮跳过。 */
    DECOMPOSE,

    /** 兜底值（ADR-066 §7.3）：只有 Schema 用 {@code x-tioka-fallback} 声明的字段可取值 → VALID_WITH_UNKNOWN。 */
    FALLBACK_VALUE,

    /** 生成人工补录任务，结局 {@code HUMAN_PENDING}（ADR-066 §7.4）。 */
    HUMAN,

    /** 结局 {@code FAILED}。 */
    FAIL;

    /** 是否为合法终止步骤（ADR-066 §7.1：列表末步必须是其一）。 */
    public boolean terminal() {
        return this == FALLBACK_VALUE || this == HUMAN || this == FAIL;
    }

    /** 宽松解析（大小写不敏感），识别不了返回 {@code null}。 */
    public static FallbackStep of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
