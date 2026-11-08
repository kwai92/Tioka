package com.tioka.knowledge.api.spi;

import java.util.List;

/**
 * 外部知识库 SPI（ADR-057 / KNW-16）。检索与健康为必需能力。
 */
public interface KnowledgeProvider {

    String type();

    KnowledgeProviderCapabilities capabilities();

    List<KnowledgeSnippet> retrieve(KnowledgeRetrieveRequest request);

    void healthCheck();
}
