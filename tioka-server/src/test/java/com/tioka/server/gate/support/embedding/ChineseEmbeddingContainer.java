package com.tioka.server.gate.support.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

/**
 * ENG-04d：本地中文 embedding + rerank 契约端点（供 KNW-07 / G3 可复现）。
 * <p>默认进程内 OpenAI 兼容桩（确定性中文哈希向量，低内存）；
 * 设置 {@code TIOKA_G3_EMBEDDING_IMAGE} 时改为拉起真实容器（须自带 OpenAI 兼容 {@code /v1/embeddings}）。
 */
public final class ChineseEmbeddingContainer implements AutoCloseable {

    public static final int DEFAULT_DIM = 32;
    private static final String ENV_IMAGE = "TIOKA_G3_EMBEDDING_IMAGE";

    private final Mode mode;
    private final MockWebServer server;
    private final GenericContainer<?> container;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private enum Mode { STUB, CONTAINER }

    private ChineseEmbeddingContainer(Mode mode, MockWebServer server, GenericContainer<?> container) {
        this.mode = mode;
        this.server = server;
        this.container = container;
    }

    public static ChineseEmbeddingContainer start() {
        String image = System.getenv(ENV_IMAGE);
        if (image != null && !image.isBlank()) {
            return startContainer(image.trim());
        }
        return startStub();
    }

    private static ChineseEmbeddingContainer startStub() {
        try {
            MockWebServer server = new MockWebServer();
            ChineseEmbeddingContainer c = new ChineseEmbeddingContainer(Mode.STUB, server, null);
            server.setDispatcher(c.stubDispatcher());
            server.start();
            return c;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("resource")
    private static ChineseEmbeddingContainer startContainer(String image) {
        GenericContainer<?> box = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(8080)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));
        box.start();
        return new ChineseEmbeddingContainer(Mode.CONTAINER, null, box);
    }

    public String baseUrl() {
        if (mode == Mode.CONTAINER) {
            return "http://" + container.getHost() + ":" + container.getMappedPort(8080);
        }
        return server.url("/").toString().replaceAll("/$", "");
    }

    public Mode mode() {
        return mode;
    }

    /** OpenAI 兼容 embeddings；返回向量维度。 */
    public int embedSmoke(String text) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", "bge-small-zh");
        body.put("dimensions", DEFAULT_DIM);
        body.putArray("input").add(text);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/embeddings"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .timeout(Duration.ofSeconds(15))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("embeddings HTTP " + resp.statusCode() + " " + resp.body());
        }
        JsonNode data = json.readTree(resp.body()).path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("empty embedding data");
        }
        return data.get(0).path("embedding").size();
    }

    /** 简易 rerank：按 query 与文档哈希距离排序，返回 top 文档下标。 */
    public int rerankTopIndex(String query, String... docs) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", "bge-reranker-base");
        body.put("query", query);
        ArrayNode arr = body.putArray("documents");
        for (String d : docs) {
            arr.add(d);
        }
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/rerank"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .timeout(Duration.ofSeconds(15))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("rerank HTTP " + resp.statusCode() + " " + resp.body());
        }
        return json.readTree(resp.body()).path("results").get(0).path("index").asInt();
    }

    private Dispatcher stubDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    String path = request.getPath() == null ? "" : request.getPath();
                    if ("POST".equals(request.getMethod()) && path.endsWith("/v1/embeddings")) {
                        return embedding(request.getBody().readUtf8());
                    }
                    if ("POST".equals(request.getMethod()) && path.endsWith("/v1/rerank")) {
                        return rerank(request.getBody().readUtf8());
                    }
                    if ("GET".equals(request.getMethod()) && (path.equals("/health") || path.equals("/"))) {
                        return new MockResponse().setResponseCode(200).setBody("ok");
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
                }
            }
        };
    }

    private MockResponse embedding(String raw) throws Exception {
        JsonNode body = json.readTree(raw);
        int dim = body.path("dimensions").asInt(DEFAULT_DIM);
        if (dim <= 0) {
            dim = DEFAULT_DIM;
        }
        ArrayNode inputs = body.path("input").isArray()
                ? (ArrayNode) body.path("input")
                : json.createArrayNode().add(body.path("input").asText(""));
        ObjectNode root = json.createObjectNode();
        root.put("object", "list");
        ArrayNode data = root.putArray("data");
        for (int i = 0; i < inputs.size(); i++) {
            ObjectNode item = data.addObject();
            item.put("object", "embedding");
            item.put("index", i);
            ArrayNode emb = item.putArray("embedding");
            for (float v : hashEmbedZh(inputs.get(i).asText(""), dim)) {
                emb.add(v);
            }
        }
        root.putObject("usage").put("prompt_tokens", Math.max(1, inputs.size()))
                .put("total_tokens", Math.max(1, inputs.size()));
        return new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(root));
    }

    private MockResponse rerank(String raw) throws Exception {
        JsonNode body = json.readTree(raw);
        String query = body.path("query").asText("");
        JsonNode docs = body.path("documents");
        ObjectNode root = json.createObjectNode();
        ArrayNode results = root.putArray("results");
        for (int i = 0; i < docs.size(); i++) {
            ObjectNode row = results.addObject();
            row.put("index", i);
            row.put("relevance_score", zhOverlap(query, docs.get(i).asText("")));
        }
        // 按 relevance_score 降序
        java.util.List<JsonNode> ranked = new java.util.ArrayList<>();
        results.forEach(ranked::add);
        ranked.sort((a, b) -> Double.compare(
                b.path("relevance_score").asDouble(), a.path("relevance_score").asDouble()));
        ArrayNode sorted = json.createArrayNode();
        ranked.forEach(sorted::add);
        root.set("results", sorted);
        return new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(root));
    }

    /** 中文友好确定性向量：对 Unicode code point 混洗，区分「华东」「华南」等短词。 */
    static float[] hashEmbedZh(String text, int dim) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String norm = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
            md.update(norm.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            float[] out = new float[dim];
            for (int i = 0; i < dim; i++) {
                int b = digest[i % digest.length] & 0xff;
                int cp = norm.isEmpty() ? 0 : norm.codePointAt(i % norm.length());
                out[i] = ((b ^ (cp & 0xff)) / 255f) * 2f - 1f;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static double zhOverlap(String query, String doc) {
        if (query == null || doc == null || query.isEmpty() || doc.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (int i = 0; i < query.length(); ) {
            int cp = query.codePointAt(i);
            i += Character.charCount(cp);
            if (doc.indexOf(cp) >= 0) {
                hit++;
            }
        }
        return hit / (double) Math.max(1, query.codePointCount(0, query.length()));
    }

    @Override
    public void close() {
        if (server != null) {
            try {
                server.shutdown();
            } catch (IOException ignored) {
            }
        }
        if (container != null) {
            container.stop();
        }
    }
}
