package com.tioka.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundation.common.id.Ids;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /*
     * ② 取证探针（2026-11-24）。加入原因：既有诊断在本失败形态下**恒为空**——
     * `diagnostic()` 只在 `lastStreamFailure != null` 时附加 SSE onError，而实测失败消息是裸 `[]`
     * 无该后缀，且 `postRunCollect` 末尾「SSE 订阅异常终止且零事件」的 AssertionError 也未抛
     * （`failure.get()==null`），说明 **onError 从未触发、流是 onComplete 正常结束但零事件**。
     * 原设计只能取证「异常中断」，对「正常结束但无事件」这一形态完全失明，
     * 故上批「下次失败时抓 lastStreamFailure」的计划注定落空。
     *
     * 关键盲区：`onNext` 只解析 `data:` 前缀行，其余整行丢弃（见其中 `else if (line.isEmpty())`
     * 之后的 else 分支），因此**服务端若返回非 2xx + 非 SSE 错误体（如 JSON 错误、网关 502 HTML），
     * 表现就是「零事件 + 正常 onComplete」**，与实测签名完全吻合；而 HTTP 状态码原先被
     * `future.get(...)` 外的 `catch (Exception ignored)` 丢弃。故补齐三项：状态码、收到的原始行数
     * （含非 data: 行）与其样本、future 终态。
     */
    /** 响应状态码（-1 表示未能取得，例如 future 未完成即被 cancel）。 */
    private volatile int lastStatusCode = -1;
    /** 响应 Content-Type，用于判断服务端是否真的按 SSE 应答。 */
    private volatile String lastContentType;
    /** onNext 收到的原始行总数（含被丢弃的非 data: 行）。 */
    private final AtomicInteger lastRawLineCount = new AtomicInteger();
    /** 被丢弃的非 data: 行样本（最多 5 条），用于识别错误体/心跳/代理插入内容。 */
    private volatile List<String> lastNonDataLines = List.of();
    /** onComplete 是否触发（区分「服务端正常关流」与「等待超时被 cancel」）。 */
    private volatile boolean lastCompleted;
    /** 等待循环的退出原因，区分「拿到终态事件」「流已结束」「超时」。 */
    private volatile String lastWaitExitReason = "timeout";

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
            // ② 取证守卫：若第二次 run 零事件，下面的 second.get(0) 会抛 IndexOutOfBoundsException，
            // 那样**根本走不到带诊断信息的断言**，诊断等于白加。故先显式断言非空以带出取证。
            assertFalse(second.isEmpty(), () -> "第二次 run 应收到 AG-UI 事件: " + diagnostic(second));
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
        // 重置 ② 取证探针（各测试方法可能多次调用本方法）
        lastStreamFailure = null;
        lastStatusCode = -1;
        lastContentType = null;
        lastRawLineCount.set(0);
        lastNonDataLines = List.of();
        lastCompleted = false;
        lastWaitExitReason = "timeout";
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
        Flow.Subscriber<String> lineSubscriber = new Flow.Subscriber<String>() {
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
                        lastRawLineCount.incrementAndGet();
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
                        } else {
                            // ② 取证：原先整行丢弃，导致「服务端返回非 SSE 错误体」这一形态无从识别。
                            // 保留最多 5 条样本（截断到 200 字符，避免把 HTML 错误页整篇灌进日志）。
                            List<String> prev = lastNonDataLines;
                            if (prev.size() < 5) {
                                List<String> copy = new ArrayList<>(prev);
                                copy.add(line.length() > 200 ? line.substring(0, 200) + "…" : line);
                                lastNonDataLines = copy;
                            }
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
                        lastCompleted = true;
                        done.set(true);
                    }
                };
        // ② 取证：BodyHandlers.fromLineSubscriber(sub) 的 future 完成值是 Void，**拿不到状态码**；
        // 改用自定义 BodyHandler，在 apply(ResponseInfo) 里捕获状态码与 Content-Type，
        // 异步模型与订阅逻辑完全不变（仍走 fromLineSubscriber 的行订阅器）。
        var future = client.sendAsync(req, responseInfo -> {
            lastStatusCode = responseInfo.statusCode();
            lastContentType = responseInfo.headers().firstValue("Content-Type").orElse(null);
            return HttpResponse.BodySubscribers.fromLineSubscriber(lineSubscriber);
        });

        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (events.stream().anyMatch(e -> {
                String t = e.path("type").asText();
                return AgUiEventType.RUN_FINISHED.equals(t) || AgUiEventType.RUN_ERROR.equals(t);
            })) {
                lastWaitExitReason = "terminal-event";
                break;
            }
            if (done.get() && future.isDone()) {
                lastWaitExitReason = "stream-ended";
                break;
            }
            Thread.sleep(40);
        }
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // ② 取证：原先 `catch (Exception ignored)` 把超时/异常信息整个丢掉，
            // 无法区分「服务端一直不发事件」与「HTTP 层未完成」。
            lastWaitExitReason = lastWaitExitReason + "+future.get失败:"
                    + (e instanceof TimeoutException ? "TimeoutException(2s)" : e.getClass().getSimpleName());
            future.cancel(true);
        }
        List<JsonNode> snapshot = new ArrayList<>(events);
        if (snapshot.isEmpty() && failure.get() != null) {
            throw new AssertionError("SSE 订阅异常终止且零事件: " + failure.get() + diagnostic(snapshot),
                    failure.get());
        }
        return snapshot;
    }

    /**
     * 断言消息附带 ② 的完整取证信息（2026-11-24 增强）。
     *
     * <p>原实现只附 {@code lastStreamFailure}，但实测失败形态是「流正常 onComplete、60s 内零事件」，
     * 此时 {@code lastStreamFailure} 恒为 null → 消息退化成裸 {@code []}，无任何可归因信息。
     * 现补齐四类事实，足以区分互斥的候选根因：
     * <ul>
     *   <li><b>HTTP 状态码 + Content-Type</b>：非 2xx 或非 {@code text/event-stream} ⇒ 服务端根本没进
     *       SSE 流程（如鉴权失败、agent 版本未发布、网关错误页），而非「进了但没发事件」；</li>
     *   <li><b>原始行数 + 非 data: 行样本</b>：行数 &gt; 0 而事件数为 0 ⇒ 服务端发了内容但格式不是
     *       {@code data:} 前缀（协议不符或错误体），样本直接暴露内容；</li>
     *   <li><b>等待退出原因</b>：区分 {@code terminal-event} / {@code stream-ended} / {@code timeout}，
     *       并保留 {@code future.get} 的失败类型；</li>
     *   <li><b>onComplete 与 onError</b>：区分「服务端主动关流」与「订阅异常中断」。</li>
     * </ul>
     */
    private String diagnostic(List<JsonNode> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("events=").append(events);
        sb.append("\n  [诊断] HTTP status=").append(lastStatusCode)
                .append("（-1=未取得）, Content-Type=").append(lastContentType);
        sb.append("\n  [诊断] 原始行数=").append(lastRawLineCount.get())
                .append("，解析出的事件数=").append(events.size())
                .append(lastRawLineCount.get() > 0 && events.isEmpty()
                        ? " ⇒ 服务端有输出但均非 data: 前缀行（协议不符或返回了错误体）"
                        : "");
        List<String> nonData = lastNonDataLines;
        if (!nonData.isEmpty()) {
            sb.append("\n  [诊断] 非 data: 行样本（最多 5 条）=").append(nonData);
        }
        sb.append("\n  [诊断] onComplete=").append(lastCompleted)
                .append("，onError=").append(lastStreamFailure == null ? "未触发" : lastStreamFailure)
                .append("，等待退出原因=").append(lastWaitExitReason);
        return sb.toString();
    }
}
