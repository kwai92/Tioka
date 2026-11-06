package com.tioka.knowledge.infrastructure.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.system.api.spi.EgressGuard;
import com.tioka.system.api.spi.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 兼容 /embeddings；失败回退 HashEmbeddingClient 并打 WARN（CI 不破）。
 * 建议配置 tioka.knowledge.embedding.dim=32 与 schema-pg vector(32) 对齐。
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final String baseUrl;
    private final String apiKeyRef;
    private final String model;
    private final int dim;
    private final SecretStore secretStore;
    private final EgressGuard egressGuard;
    private final EmbeddingClient fallback;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKeyRef, String model, int dim,
                                           SecretStore secretStore, EgressGuard egressGuard,
                                           EmbeddingClient fallback, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKeyRef = apiKeyRef == null ? "" : apiKeyRef;
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        this.dim = Math.max(1, dim);
        this.secretStore = secretStore;
        this.egressGuard = egressGuard;
        this.fallback = fallback == null ? new HashEmbeddingClient(this.dim) : fallback;
    }

    @Override
    public double[] embed(String text) {
        if (baseUrl.isBlank()) {
            return fallback.embed(text);
        }
        try {
            if (egressGuard != null) {
                egressGuard.assertAllowed(baseUrl);
            }
            String apiKey = resolveApiKey();
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", text == null ? "" : text,
                    "dimensions", dim
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl) + "/embeddings"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("status=" + resp.statusCode() + " body=" + resp.body());
            }
            return fitDim(parseEmbedding(resp.body()));
        } catch (Exception e) {
            log.warn("[knowledge.embedding] OpenAI-compatible failed, fallback hash: {}", e.getMessage());
            return fallback.embed(text);
        }
    }

    @Override
    public int dimension() {
        return dim;
    }

    private double[] parseEmbedding(String json) throws Exception {
        JsonNode root = mapper.readTree(json == null ? "{}" : json);
        JsonNode arr = root.path("data").path(0).path("embedding");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("empty embedding");
        }
        double[] v = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = arr.get(i).asDouble();
        }
        return v;
    }

    private double[] fitDim(double[] src) {
        if (src.length == dim) return src;
        double[] out = new double[dim];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, dim));
        return out;
    }

    private String resolveApiKey() {
        if (secretStore == null || apiKeyRef.isBlank()) return "";
        String ns = "model-provider";
        String key = apiKeyRef;
        if (apiKeyRef.contains("::")) {
            String[] parts = apiKeyRef.split("::", 2);
            ns = parts[0];
            key = parts[1];
        }
        String apiKey = secretStore.get(ns, key);
        return apiKey == null ? "" : apiKey;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
