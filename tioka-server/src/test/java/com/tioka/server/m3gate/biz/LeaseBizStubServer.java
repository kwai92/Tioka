package com.tioka.server.m3gate.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ENG-04d：业务系统 HTTP 桩（BIZ-07 OpenAPI 最小子集）。
 * 合入 PK-01 后移入 tioka-server/.../gate/support/biz/。
 */
public final class LeaseBizStubServer implements AutoCloseable {

    private final MockWebServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ObjectNode> contracts = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();

    private LeaseBizStubServer(MockWebServer server) {
        this.server = server;
    }

    public static LeaseBizStubServer start() {
        try {
            LeaseBizStubServer stub = new LeaseBizStubServer(new MockWebServer());
            stub.seedDemoContract();
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

    public List<JsonNode> events() {
        return List.copyOf(events);
    }

    /** 供用例触发：模拟业务系统发出 lease.contract.submitted。 */
    public JsonNode emitSubmitted(String contractKey, String storeCode) {
        ObjectNode env = json.createObjectNode();
        env.put("eventId", UUID.randomUUID().toString());
        env.put("type", "lease.contract.submitted");
        env.put("occurredAt", java.time.Instant.now().toString());
        env.put("source", "lease-biz");
        env.put("subjectKey", "lease:" + storeCode + ":" + contractKey + ":v0");
        ObjectNode data = env.putObject("data");
        data.put("contractKey", contractKey);
        data.put("storeCode", storeCode);
        data.put("kind", "MASTER");
        data.put("assignee", "lawyer.demo");
        data.putArray("priorKeys");
        data.putArray("files").addObject()
                .put("fileId", "f1")
                .put("sha256", "abc")
                .put("mime", "application/pdf");
        events.add(env);
        return env;
    }

    private void seedDemoContract() {
        ObjectNode c = json.createObjectNode();
        c.put("contractKey", "C-DEMO");
        c.put("storeCode", "S001");
        c.put("kind", "MASTER");
        c.put("assignee", "lawyer.demo");
        c.putArray("priorKeys");
        c.put("title", "演示主合同");
        contracts.put("C-DEMO", c);
    }

    private Dispatcher dispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    String path = request.getPath() == null ? "" : request.getPath();
                    String method = request.getMethod();
                    if ("GET".equals(method) && path.matches("/api/contracts/[^/]+$")) {
                        String key = path.substring("/api/contracts/".length());
                        ObjectNode c = contracts.get(key);
                        if (c == null) {
                            return json(404, err("NOT_FOUND", "contract " + key));
                        }
                        return json(200, c);
                    }
                    if ("GET".equals(method) && path.matches("/api/contracts/[^/]+/files$")) {
                        ObjectNode body = json.createObjectNode();
                        ArrayNode files = body.putArray("files");
                        files.addObject()
                                .put("fileId", "f1")
                                .put("downloadUrl", baseUrl() + "/files/f1")
                                .put("expiresAt", java.time.Instant.now().plusSeconds(300).toString())
                                .put("sha256", "abc")
                                .put("mime", "application/pdf");
                        return json(200, body);
                    }
                    if ("POST".equals(method) && path.matches("/api/contracts/[^/]+/model-drafts$")) {
                        return withIdempotency(request, () -> {
                            ObjectNode ack = json.createObjectNode();
                            ack.put("contractKey", path.split("/")[3]);
                            ack.put("draftId", "draft-" + UUID.randomUUID());
                            return ack;
                        });
                    }
                    if ("POST".equals(method) && path.matches("/api/review-runs/[^/]+/findings$")) {
                        return withIdempotency(request, () -> {
                            ObjectNode ack = json.createObjectNode();
                            ack.put("reviewRunId", path.split("/")[3]);
                            ack.put("round", 1);
                            return ack;
                        });
                    }
                    if ("PUT".equals(method) && path.matches("/api/stores/[^/]+/lease-state$")) {
                        return withIdempotency(request, () -> {
                            ObjectNode ack = json.createObjectNode();
                            ack.put("storeCode", path.split("/")[3]);
                            ack.put("writtenAt", java.time.Instant.now().toString());
                            return ack;
                        });
                    }
                    if ("POST".equals(method) && path.equals("/api/events")) {
                        JsonNode body = json.readTree(request.getBody().readUtf8());
                        events.add(body);
                        return new MockResponse().setResponseCode(202);
                    }
                    if ("GET".equals(method) && path.startsWith("/api/checkpoint-sets/")) {
                        ObjectNode body = json.createObjectNode();
                        body.put("version", path.substring(path.lastIndexOf('/') + 1));
                        body.putArray("checkpoints").addObject()
                                .put("code", "CP-01")
                                .put("title", "demo")
                                .put("severity", "MEDIUM");
                        return json(200, body);
                    }
                    if ("GET".equals(method) && path.startsWith("/api/thresholds")) {
                        ObjectNode body = json.createObjectNode();
                        body.put("configVersion", "1");
                        body.putObject("thresholds").put("demo", 1);
                        return json(200, body);
                    }
                    return json(404, err("NOT_FOUND", path));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
                }
            }
        };
    }

    private MockResponse withIdempotency(RecordedRequest request, BodySupplier supplier) throws Exception {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            return json(400, err("IDEMPOTENCY_REQUIRED", "Idempotency-Key required"));
        }
        String cached = idempotency.get(key);
        if (cached != null) {
            return new MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(cached);
        }
        String body = json.writeValueAsString(supplier.get());
        idempotency.put(key, body);
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private MockResponse json(int code, JsonNode body) throws Exception {
        return new MockResponse().setResponseCode(code)
                .addHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(body));
    }

    private ObjectNode err(String code, String message) {
        ObjectNode n = json.createObjectNode();
        n.put("code", code);
        n.put("message", message);
        return n;
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    @FunctionalInterface
    private interface BodySupplier {
        JsonNode get() throws Exception;
    }
}
