package com.tioka.eval.api.dataset;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 评测用例（EVO-02）。
 */
public record EvalCaseView(
        String caseId,
        String datasetId,
        int version,
        JsonNode input,
        JsonNode expected,
        String source
) {}
