package com.tioka.eval.api.spi;

/**
 * 评估器打分结果（ADR-031）。
 */
public record EvalScore(
        Double score,
        boolean passed,
        String reason
) {}
