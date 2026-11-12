package com.tioka.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.agui.AgUiEventType;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HCI-06b：AG-UI 端点验收（M2-04）。
 */
@Tag("it")
class AgUiEndpointIT {

    private final ObjectMapper om = new ObjectMapper();
    /** C-20：记录 SSE 订阅侧 onError 根因，避免 flake 失败时只剩「零事件」而无从归因。 */
    private volatile Throwable lastStreamFailure;

    @Test
    void streamsAndFinishes() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            String aguiThread = Ids.newId("agui");
            String runId = Ids.newId("run");
            List<JsonNode> events = postRunCollect(di.baseUrlA(), bundle, aguiThread, runId,
                    "ping", List.of(), 60_000);

            assertFalse(events.isEmpty(), () -> "应收到 AG-UI 事件: " + diagnostic(events));
            assertEquals(AgUiEventType.RUN_STARTED, events.get(0).path("type").asText(), events::toString);
            assertEquals(aguiThread, events.get(0).path("threadId").asText());
            assertEquals(runId, events.get(0).path("runId").asText());
            assertTrue(events.stream().anyMatch(e -> AgUiEventType.RUN_FINISHED.equals(e.path("type").asText())),
                    () -> "应有 RUN_FINISHED: " + diagnostic(events));
            assertTrue(events.stream().anyMatch(e ->
                            AgUiEventType.TEXT_MESSAGE_CONTENT.equals(e.path("type").asText())
                                    || AgUiEventType.TEXT_MESSAGE_END.equals(e.path("type").asText())),
                    () -> "应有文本消息: " + diagnostic(events));
        }
    }

    @Test
    void clientToolsRejected() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            List<JsonNode> events = postRunCollect(di.baseUrlA(), bundle, Ids.newId("agui"),
                    Ids.newId("run"), "hi",
                    List.of(Map.of("name", "browser")), 10_000);
            assertEquals(1, events.size(), () -> diagnostic(events));
            assertEquals(AgUiEventType.RUN_ERROR, events.get(0).path("type").asText());
            assertEquals("CLIENT_TOOLS_UNSUPPORTED", events.get(0).path("code").asText());
        }
    }

    @Test
    void threadIdReused() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            String aguiThread = Ids.newId("agui");
            List<JsonNode> first = postRunCollect(di.baseUrlA(), bundle, aguiThread,
                    Ids.newId("run"), "ping", List.of(), 60_000);
            assertTrue(first.stream().anyMatch(e -> AgUiEventType.RUN_FINISHED.equals(e.path("type").asText())),
                    () -> diagnostic(first));

            List<JsonNode> second = postRunCollect(di.baseUrlA(), bundle, aguiThread,
                    Ids.newId("run"), "ping again", List.of(), 60_000);
            assertEquals(AgUiEventType.RUN_STARTED, second.get(0).path("type").asText());
            assertEquals(aguiThread, second.get(0).path("threadId").asText());
            assertTrue(second.stream().anyMatch(e -> AgUiEventType.RUN_FINISHED.equals(e.path("type").asText())),
                    () -> diagnostic(second));
        }
    }

    private List<JsonNode> postRunCollect(String baseUrl, GateSeed.Bundle bundle,
                                          String threadId, String runId, String text,
                                          List<Map<String, Object>> tools, long waitMs)
            throws Exception {
        String body = om.writeValueAsString(Map.of(
                "threadId", threadId,
                "runId", runId,
                "messages", List.of(Map.of("id", "m1", "role", "user", "content", text)),
                "tools", tools,
                "state", Map.of(),
                "context", List.of(),
                "forwardedProps", Map.of()));

        List<JsonNode> events = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        lastStreamFailure = null;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/agui/agents/" + bundle.agentVersionId() + "/run"))
                .timeout(Duration.ofMillis(waitMs + 5_000))
                .header("Authorization", "Bearer " + bundle.token())
                .header("X-Workspace-Id", bundle.workspaceId())
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        StringBuilder data = new StringBuilder();
        var future = client.sendAsync(req, HttpResponse.BodyHandlers.fromLineSubscriber(
                new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        this.subscription = s;
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String line) {
                        if (line == null) {
                            return;
                        }
                        if (line.startsWith("data:")) {
                            String payload = line.substring(5).trim();
                            if (!payload.isEmpty() && !payload.equals("[DONE]")) {
                                try {
                                    events.add(om.readTree(payload));
                                } catch (Exception ignored) {
                                    // skip non-json
                                }
                            }
                            data.setLength(0);
                        } else if (line.isEmpty()) {
                            // event boundary
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        failure.set(t);
                        lastStreamFailure = t;
                        done.set(true);
                    }

                    @Override
                    public void onComplete() {
                        done.set(true);
                    }
                }));

        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (events.stream().anyMatch(e -> {
                String t = e.path("type").asText();
                return AgUiEventType.RUN_FINISHED.equals(t) || AgUiEventType.RUN_ERROR.equals(t);
            })) {
                break;
            }
            if (done.get() && future.isDone()) {
                break;
            }
            Thread.sleep(40);
        }
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
        }
        List<JsonNode> snapshot = new ArrayList<>(events);
        if (snapshot.isEmpty() && failure.get() != null) {
            throw new AssertionError("SSE 订阅异常终止且零事件: " + failure.get(), failure.get());
        }
        return snapshot;
    }

    /** 断言消息附带 SSE onError 根因（C-20 诊断增强）。 */
    private String diagnostic(List<JsonNode> events) {
        Throwable t = lastStreamFailure;
        return events + (t == null ? "" : " | SSE onError: " + t);
    }
}
