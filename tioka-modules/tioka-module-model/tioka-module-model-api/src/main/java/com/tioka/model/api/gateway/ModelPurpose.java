package com.tioka.model.api.gateway;

/** 调用用途，用于路由、配额归集与用量统计。 */
public enum ModelPurpose {
    CHAT, FLOW, EMBEDDING, RERANK, DECISION, COMPACTION
}
