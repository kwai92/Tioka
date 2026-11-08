package com.tioka.knowledge.api.spi;

import java.util.Map;

/**
 * KnowledgeProvider 检索请求（ADR-057）。
 */
public record KnowledgeRetrieveRequest(
        String externalKbId,
        String query,
        int topK,
        Double scoreThreshold,
        Map<String, Object> metadataFilter
) {}
