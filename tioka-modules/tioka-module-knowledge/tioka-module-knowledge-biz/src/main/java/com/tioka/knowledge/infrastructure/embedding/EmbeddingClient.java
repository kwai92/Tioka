package com.tioka.knowledge.infrastructure.embedding;

/** 文本向量化客户端；维度与 schema embedding_vec / local hash 对齐（默认 32）。 */
public interface EmbeddingClient {
    double[] embed(String text);

    int dimension();
}
