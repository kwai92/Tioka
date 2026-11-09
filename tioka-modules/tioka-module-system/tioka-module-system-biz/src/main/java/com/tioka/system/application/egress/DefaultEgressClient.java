package com.tioka.system.application.egress;

import com.tioka.system.api.obs.ExtInteractionRecorder;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.EgressGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * 出网唯一通道（ADR-021）：出网守卫 + HTTP 发送 + 外部交互台账（OBS-04a / ADR-074）。
 *
 * <p>台账口径：所有经守卫且被允许的请求写一条 OUT {@code http} 记录（内容哈希 + 大小 + 时延）；
 * 被守卫拒绝的请求不走本类（守卫抛异常），仍按 ADR-072 记审计。
 * 业务侧如需 {@code im}/{@code biz} 等更具体的 kind 或对方回执号，自行再写一条并补
 * {@code external_ref}（ADR-074 §2：出网在 EgressClient 一处写即可覆盖大部分）。
 */
@Service
public class DefaultEgressClient implements EgressClient {
    private final EgressGuard guard;
    /** 台账可选注入：单测或未装配环境静默降级（台账不阻断出网）。 */
    private final ObjectProvider<ExtInteractionRecorder> ledger;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DefaultEgressClient(EgressGuard guard,
                               ObjectProvider<ExtInteractionRecorder> ledger) {
        this.guard = guard;
        this.ledger = ledger;
    }

    @Override
    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        guard.assertAllowed(request.uri().toString());
        long start = System.nanoTime();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            recordLedger(request, resp.statusCode(), start,
                    resp.body() == null ? 0 : resp.body().length(), null);
            return resp;
        } catch (IOException e) {
            recordLedger(request, null, start, 0, e);
            throw e;
        }
    }

    @Override
    public HttpResponse<InputStream> sendStreaming(HttpRequest request) throws IOException, InterruptedException {
        guard.assertAllowed(request.uri().toString());
        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // 流式响应不消费 body 记大小——记 -1 表示未知，避免为台账读完整流
            recordLedger(request, resp.statusCode(), start, -1, null);
            return resp;
        } catch (IOException e) {
            recordLedger(request, null, start, 0, e);
            throw e;
        }
    }

    /* ---------- 台账写入 ---------- */

    private void recordLedger(HttpRequest request, Integer httpStatus, long startNanos,
                              long responseSize, IOException error) {
        ExtInteractionRecorder recorder = ledger.getIfAvailable();
        if (recorder == null) {
            return;
        }
        try {
            URI uri = request.uri();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            ExtInteractionRecorder.ExtInteractionRecord record =
                    new ExtInteractionRecorder.ExtInteractionRecord(
                            ExtInteractionRecorder.Direction.OUT,
                            "http",
                            uri.getHost(),
                            uri.getHost() + uri.getRawPath(),
                            request.method(),
                            statusOf(error),
                            httpStatus,
                            null,
                            null,
                            latencyMs,
                            Map.of(
                                    "request_digest", sha256(uri + "\n" + request.method()),
                                    "request_size", String.valueOf(bodySize(request)),
                                    "response_size", String.valueOf(responseSize)
                            ));
            recorder.record(record);
        } catch (RuntimeException e) {
            // 台账异常已在 recorder 内降级为日志；此处再兜一层，保证永不影响出网主流程
        }
    }

    private static ExtInteractionRecorder.Status statusOf(IOException error) {
        if (error == null) {
            return ExtInteractionRecorder.Status.OK;
        }
        return error instanceof java.net.http.HttpTimeoutException
                ? ExtInteractionRecorder.Status.TIMEOUT
                : ExtInteractionRecorder.Status.ERROR;
    }

    private static long bodySize(HttpRequest request) {
        return request.bodyPublisher()
                .map(java.net.http.HttpRequest.BodyPublisher::contentLength)
                .orElse(-1L);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
