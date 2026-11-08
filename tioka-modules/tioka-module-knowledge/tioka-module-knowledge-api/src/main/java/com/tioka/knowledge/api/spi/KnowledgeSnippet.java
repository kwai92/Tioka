package com.tioka.knowledge.api.spi;

import java.util.Map;

/**
 * 外部/联邦检索片段（ADR-057）。
 */
public record KnowledgeSnippet(
        String text,
        Double externalScore,
        String externalDocId,
        String title,
        String sourceUrl,
        String location,
        Map<String, Object> metadata,
        String contentHash
) {}
