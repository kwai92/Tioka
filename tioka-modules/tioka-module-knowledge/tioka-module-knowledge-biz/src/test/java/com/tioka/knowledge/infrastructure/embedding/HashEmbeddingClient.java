package com.tioka.knowledge.infrastructure.embedding;

import java.nio.charset.StandardCharsets;

/**
 * 测试用本地哈希嵌入（MH-04 起生产走 ModelGateway；KNW-01 要求 Hash 仅留在 test）。
 */
public class HashEmbeddingClient {
    private final int dim;

    public HashEmbeddingClient() {
        this(32);
    }

    public HashEmbeddingClient(int dim) {
        this.dim = Math.max(1, dim);
    }

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

    public int dimension() {
        return dim;
    }
}
