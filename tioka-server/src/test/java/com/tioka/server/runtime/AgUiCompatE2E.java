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

import static org.junit.jupiter.api.Assertions.*;

/**
 * AG-UI 协议兼容 E2E（后端侧）：真实 Agent + LLM 桩，经 {@code /api/agui/.../run} 完成一轮对话。
 * Playwright {@code examples/agui-client/} 留给 FE / G2；本用例覆盖网关契约。
 */
@Tag("it")
class AgUiCompatE2E {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void agentRoundTripViaAgUiProtocol() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            String threadId = Ids.newId("agui");
            String runId = Ids.newId("run");
            String body = om.writeValueAsString(Map.of(
                    "threadId", threadId,
                    "runId", runId,
                    "messages", List.of(Map.of(
                            "id", "u1", "role", "user", "content", "ping")),
                    "tools", List.of(),
                    "state", Map.of(),
                    "context", List.of(),
                    "forwardedProps", Map.of()));

            List<JsonNode> events = new CopyOnWriteArrayList<>();
            AtomicBoolean done = new AtomicBoolean(false);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(di.baseUrlA() + "/api/agui/agents/" + bundle.agentVersionId() + "/run"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + bundle.token())
                    .header("X-Workspace-Id", bundle.workspaceId())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var future = client.sendAsync(req, HttpResponse.BodyHandlers.fromLineSubscriber(
                    new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                        @Override public void onNext(String line) {
                            if (line != null && line.startsWith("data:")) {
                                String payload = line.substring(5).trim();
                                if (!payload.isEmpty()) {
                                    try { events.add(om.readTree(payload)); } catch (Exception ignored) {}
                                }
                            }
                        }
                        @Override public void onError(Throwable t) { done.set(true); }
                        @Override public void onComplete() { done.set(true); }
                    }));

            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline
                    && events.stream().noneMatch(e -> AgUiEventType.RUN_FINISHED.equals(e.path("type").asText()))) {
                Thread.sleep(50);
            }
            try { future.get(3, TimeUnit.SECONDS); } catch (Exception ignored) { future.cancel(true); }

            List<String> types = new ArrayList<>();
            for (JsonNode e : events) {
                types.add(e.path("type").asText());
            }
            assertTrue(types.contains(AgUiEventType.RUN_STARTED), () -> "types=" + types);
            assertTrue(types.contains(AgUiEventType.RUN_FINISHED), () -> "types=" + types);
            assertTrue(types.contains(AgUiEventType.TEXT_MESSAGE_START)
                            || types.contains(AgUiEventType.TEXT_MESSAGE_CONTENT)
                            || types.contains(AgUiEventType.TEXT_MESSAGE_END),
                    () -> "应收到助手文本: " + types);
            assertEquals(threadId, events.get(0).path("threadId").asText());
            assertEquals(runId, events.get(0).path("runId").asText());
        }
    }
}
