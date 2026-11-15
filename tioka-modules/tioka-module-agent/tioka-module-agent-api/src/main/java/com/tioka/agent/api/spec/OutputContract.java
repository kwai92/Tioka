package com.tioka.agent.api.spec;

import java.util.List;

/**
 * Agent 结构化输出契约（ADR-037 / ADR-066）。可空表示自由文本 Agent。
 *
 * @param allowRelaxedSchema 是否允许引用非严格档（{@code x-tioka-tier: relaxed}）的 Schema（ADR-066 §3）
 * @param fallback           降级阶梯（ADR-066 §7.1）：有序步骤列表，末步必须是
 *                           {@link FallbackStep#FALLBACK_VALUE} / {@link FallbackStep#HUMAN} /
 *                           {@link FallbackStep#FAIL} 之一（发布校验检查）
 */
public record OutputContract(
        String schemaRef,
        OutputMode mode,
        Integer maxRepair,
        Boolean requireEvidence,
        OutputStrategy strategy,
        String formatModelAlias,
        Boolean allowRelaxedSchema,
        List<FallbackStep> fallback
) {
    /** ADR-066 §7.1：缺省降级阶梯。 */
    public static final List<FallbackStep> DEFAULT_FALLBACK = List.of(
            FallbackStep.REPAIR, FallbackStep.SWITCH_MODE, FallbackStep.SWITCH_MODEL, FallbackStep.HUMAN);

    public OutputContract {
        if (mode == null) {
            mode = OutputMode.FINAL;
        }
        if (maxRepair == null || maxRepair < 0) {
            maxRepair = 2;
        }
        if (requireEvidence == null) {
            requireEvidence = false;
        }
        if (strategy == null) {
            strategy = OutputStrategy.DIRECT;
        }
        if (allowRelaxedSchema == null) {
            allowRelaxedSchema = false;
        }
        fallback = (fallback == null || fallback.isEmpty()) ? DEFAULT_FALLBACK : List.copyOf(fallback);
    }

    /** 兼容旧 7 参口径（缺省降级阶梯）。 */
    public OutputContract(String schemaRef,
                          OutputMode mode,
                          Integer maxRepair,
                          Boolean requireEvidence,
                          OutputStrategy strategy,
                          String formatModelAlias,
                          Boolean allowRelaxedSchema) {
        this(schemaRef, mode, maxRepair, requireEvidence, strategy, formatModelAlias, allowRelaxedSchema, null);
    }

    public static OutputContract of(String schemaRef, OutputMode mode) {
        return new OutputContract(schemaRef, mode, 2, false, OutputStrategy.DIRECT, null, false, null);
    }
}
