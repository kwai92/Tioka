package com.tioka.eval.api.spi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单条评测样本与实际产出（ADR-031）。
 */
public record EvalSubject(
        String caseId,
        JsonNode input,
        JsonNode expected,
        JsonNode actual,
        String turnId
) {}
