package com.tioka.agent.api.spec;

/**
 * Agent 结构化输出契约（ADR-037 / ADR-066）。可空表示自由文本 Agent。
 */
public record OutputContract(
        String schemaRef,
        OutputMode mode,
        Integer maxRepair,
        Boolean requireEvidence,
        OutputStrategy strategy,
        String formatModelAlias
) {
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
    }

    public static OutputContract of(String schemaRef, OutputMode mode) {
        return new OutputContract(schemaRef, mode, 2, false, OutputStrategy.DIRECT, null);
    }
}
