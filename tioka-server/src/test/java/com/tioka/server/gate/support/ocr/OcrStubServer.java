package com.tioka.server.gate.support.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ENG-04d：OCR 桩。按 sha256 返回 classpath 录制结果；未知样本显式失败。
 */
public final class OcrStubServer implements AutoCloseable {

    private final MockWebServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonNode> bySha = new ConcurrentHashMap<>();

    private OcrStubServer(MockWebServer server) {
        this.server = server;
    }

    public static OcrStubServer start() {
        try {
            OcrStubServer stub = new OcrStubServer(new MockWebServer());
            stub.loadClasspath("ocr-fixtures/sample-scan-1.json", "sha256:sample-scan-1");
            stub.loadClasspath("ocr-fixtures/sample-scan-2.json", "sha256:sample-scan-2");
            stub.loadClasspath("ocr-fixtures/sample-scan-3.json", "sha256:sample-scan-3");
            stub.server.setDispatcher(stub.dispatcher());
            stub.server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private void loadClasspath(String resource, String key) throws IOException {
        try (InputStream in = OcrStubServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                // 无录制文件时用最小合成结果，CI 仍可起桩
                ObjectNode synth = json.createObjectNode();
                synth.put("sha256", key);
                ArrayNode pages = synth.putArray("pages");
                pages.addObject().put("page", 1).put("text", "RECORDED:" + key);
                bySha.put(key, synth);
                return;
            }
            bySha.put(key, json.readTree(in));
        }
    }

    private Dispatcher dispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    if (!"POST".equals(request.getMethod()) || !"/ocr/recognize".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(404);
                    }
                    JsonNode body = json.readTree(request.getBody().readUtf8());
                    String sha = body.path("sha256").asText("");
                    JsonNode result = bySha.get(sha);
                    if (result == null) {
                        ObjectNode err = json.createObjectNode();
                        err.put("code", "OCR_FIXTURE_MISS");
                        err.put("message", "no recording for " + sha);
                        return new MockResponse().setResponseCode(404)
                                .addHeader("Content-Type", "application/json")
                                .setBody(json.writeValueAsString(err));
                    }
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(json.writeValueAsString(result));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
                }
            }
        };
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }
}
