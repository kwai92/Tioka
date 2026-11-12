package com.tioka.eval.api.dataset;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 评测用例（EVO-02；C-18 EVO-07 按 ADR-068 第 1.2/2 节加材料单元键与标注状态）。
 *
 * @param unitKey     材料单元键；null = 未声明
 * @param labelStatus VALID / STALE / DISPUTED；非 VALID 不参与门禁统计
 * @param mustPass    逐条必须通过（回归集缺省为是）
 */
public record EvalCaseView(
        String caseId,
        String datasetId,
        int version,
        JsonNode input,
        JsonNode expected,
        String source,
        String unitKey,
        String labelStatus,
        boolean mustPass
) {}
