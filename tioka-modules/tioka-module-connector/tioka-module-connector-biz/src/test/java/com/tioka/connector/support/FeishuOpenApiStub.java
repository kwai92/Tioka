package com.tioka.connector.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDK 内置 HTTP 桩，回放飞书 OpenAPI 录制（替代 MockWebServer，避免改 pom 依赖）。
 */
public final class FeishuOpenApiStub implements AutoCloseable {
    private final HttpServer server;
    private final List<String> tokenBodies = new CopyOnWriteArrayList<>();
    private final List<String> messageBodies = new CopyOnWriteArrayList<>();
    private final List<String> docBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger messageCalls = new AtomicInteger();
    private final List<Queued> tokenQueue = new ArrayList<>();
    private final List<Queued> messageQueue = new ArrayList<>();
    private final List<Queued> rawContentQueue = new ArrayList<>();
    private final List<Queued> createDocQueue = new ArrayList<>();

    private FeishuOpenApiStub(HttpServer server) {
        this.server = server;
    }

    public static FeishuOpenApiStub start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FeishuOpenApiStub stub = new FeishuOpenApiStub(server);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", stub::onToken);
        server.createContext("/open-apis/im/v1/messages", stub::onMessage);
        server.createContext("/open-apis/docx/v1/documents", stub::onDocuments);
        server.start();
        return stub;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void enqueueToken(int status, String body) {
        tokenQueue.add(new Queued(status, body));
    }

    public void enqueueMessage(int status, String body) {
        messageQueue.add(new Queued(status, body));
    }

    public void enqueueRawContent(int status, String body) {
        rawContentQueue.add(new Queued(status, body));
    }

    public void enqueueCreateDocument(int status, String body) {
        createDocQueue.add(new Queued(status, body));
    }

    public void enqueueTokenFromResource(String classpath) throws IOException {
        enqueueToken(200, readResource(classpath));
    }

    public void enqueueMessageFromResource(String classpath) throws IOException {
        enqueueMessage(200, readResource(classpath));
    }

    public int tokenCalls() {
        return tokenCalls.get();
    }

    public int messageCalls() {
        return messageCalls.get();
    }

    public List<String> tokenBodies() {
        return List.copyOf(tokenBodies);
    }

    public List<String> messageBodies() {
        return List.copyOf(messageBodies);
    }

    public List<String> docBodies() {
        return List.copyOf(docBodies);
    }

    private void onToken(HttpExchange ex) throws IOException {
        tokenCalls.incrementAndGet();
        tokenBodies.add(readBody(ex));
        Queued q = take(tokenQueue, 200, "{\"code\":0,\"tenant_access_token\":\"t-default\",\"expire\":7200}");
        write(ex, q.status(), q.body());
    }

    private void onMessage(HttpExchange ex) throws IOException {
        messageCalls.incrementAndGet();
        messageBodies.add(readBody(ex));
        Queued q = take(messageQueue, 200, "{\"code\":0,\"data\":{\"message_id\":\"om_mock\"}}");
        write(ex, q.status(), q.body());
    }

    private void onDocuments(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        docBodies.add(ex.getRequestMethod() + " " + path + " " + readBody(ex));
        if (path.endsWith("/raw_content") && "GET".equalsIgnoreCase(ex.getRequestMethod())) {
            Queued q = take(rawContentQueue, 200, "{\"code\":0,\"data\":{\"content\":\"hello doc\"}}");
            write(ex, q.status(), q.body());
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod()) && path.equals("/open-apis/docx/v1/documents")) {
            Queued q = take(createDocQueue, 200, "{\"code\":0,\"data\":{\"document\":{\"document_id\":\"doc_mock\"}}}");
            write(ex, q.status(), q.body());
            return;
        }
        write(ex, 404, "{\"code\":404,\"msg\":\"not found\"}");
    }

    private static Queued take(List<Queued> queue, int defaultStatus, String defaultBody) {
        synchronized (queue) {
            if (queue.isEmpty()) {
                return new Queued(defaultStatus, defaultBody);
            }
            return queue.remove(0);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void write(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readResource(String classpath) throws IOException {
        Path p = Path.of("src/test/resources").resolve(classpath);
        if (Files.isRegularFile(p)) {
            return Files.readString(p);
        }
        try (var in = FeishuOpenApiStub.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IOException("missing resource: " + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private record Queued(int status, String body) {}
}
