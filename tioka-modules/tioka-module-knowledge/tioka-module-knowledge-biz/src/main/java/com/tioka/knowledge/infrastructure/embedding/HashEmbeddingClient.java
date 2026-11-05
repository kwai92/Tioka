package com.tioka.knowledge.infrastructure.embedding;

import java.nio.charset.StandardCharsets;

/**
 * 本地默认：字频哈希向量（与历史 32 维行为一致）。
 */
public class HashEmbeddingClient implements EmbeddingClient {
    private final int dim;

    public HashEmbeddingClient() {
        this(32);
    }

    public HashEmbeddingClient(int dim) {
        this.dim = Math.max(1, dim);
    }

    @Override
    public double[] embed(String text) {
        double[] v = new double[dim];
        if (text == null || text.isBlank()) return v;
        byte[] bytes = text.toLowerCase().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            v[Math.floorMod(bytes[i] + i, v.length)] += 1.0;
        }
        double norm = 0;
        for (double x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
        return v;
    }

    @Override
    public int dimension() {
        return dim;
    }
}
