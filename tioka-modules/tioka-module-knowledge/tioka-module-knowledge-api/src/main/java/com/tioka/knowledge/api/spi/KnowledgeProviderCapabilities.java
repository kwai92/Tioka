package com.tioka.knowledge.api.spi;

/**
 * 外部知识库适配器能力声明（ADR-057）。
 */
public record KnowledgeProviderCapabilities(
        boolean listKnowledgeBases,
        boolean metadataFilter,
        boolean readSource,
        boolean nativeRerank,
        boolean documentExport
) {}
