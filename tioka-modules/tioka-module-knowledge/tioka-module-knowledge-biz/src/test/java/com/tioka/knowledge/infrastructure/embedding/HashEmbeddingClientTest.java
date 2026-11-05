package com.tioka.knowledge.infrastructure.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashEmbeddingClientTest {
    @Test
    void dimensionAndNorm() {
        HashEmbeddingClient client = new HashEmbeddingClient(32);
        assertEquals(32, client.dimension());
        double[] v = client.embed("hello tioka");
        assertEquals(32, v.length);
        double norm = 0;
        for (double x : v) norm += x * x;
        assertTrue(Math.abs(Math.sqrt(norm) - 1.0) < 1e-6);
    }
}
