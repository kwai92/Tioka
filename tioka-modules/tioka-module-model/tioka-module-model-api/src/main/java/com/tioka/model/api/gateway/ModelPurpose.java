package com.tioka.model.api.gateway;

/** 调用用途，用于路由、配额归集与用量统计。EVALUATION 与 EXTRACTION 计入空间评测预算（ADR-031）。 */
public enum ModelPurpose {
    CHAT, FLOW, EMBEDDING, RERANK, DECISION, COMPACTION, EVALUATION, EXTRACTION, OCR,
    /** AST-07（ADR-041 §4）：Prompt 模板「试运行」，仅编辑器触发，不计入业务配额归集口径。 */
    PROMPT_TEST
}
