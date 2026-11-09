package com.tioka.model.application.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.routing.Difficulty;
import com.tioka.model.api.routing.RoutingDecider;
import com.tioka.system.api.spi.EgressClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MH-06 内置外部 HTTP 决策器（ADR-024「外部 HTTP：客户自有决策服务」；HiRoute Jev 决策模型可经此接入）。
 * 出网唯一入口 {@link EgressClient}（47-GL §1.8，HttpOAuthTokenClient 先例）；
 * endpoint 配置化（{@code tioka.model.routing.http-decider.endpoint}），请求超时 = 决策超时 1.5s。
 * 协议：POST {userText, previousGroup, previousCompetence, hints} → {category, difficulty, confidence, reason}。
 */
@Component
public class HttpRoutingDecider implements RoutingDecider {

    static final long DEFAULT_TIMEOUT_MS = RoutingPlanSpecParser.DEFAULT_DECIDE_TIMEOUT_MS;

    private final EgressClient egress;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final long timeoutMs;

    public HttpRoutingDecider(EgressClient egress, ObjectMapper mapper,
                              @Value("${tioka.model.routing.http-decider.endpoint:}") String endpoint,
                              @Value("${tioka.model.routing.http-decider.timeout-ms:" + DEFAULT_TIMEOUT_MS + "}") long timeoutMs) {
        this.egress = egress;
        this.mapper = mapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    boolean configured() {
        return !endpoint.isEmpty();
    }

    @Override
    public String id() {
        return RoutingDeciderRegistry.ID_HTTP_PREFIX;
    }

    @Override
    public Decision decide(DecisionInput input) {
        if (!configured()) {
            throw new IllegalStateException("外部 HTTP 决策器未配置 endpoint "
                    + "(tioka.model.routing.http-decider.endpoint)");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("userText", input == null ? "" : input.userText());
            body.put("previousGroup", input == null ? null : input.previousGroup());
            body.put("previousCompetence", input == null ? null : input.previousCompetence());
            body.put("hints", input == null ? Map.of() : input.hints());
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = egress.send(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("外部决策服务返回 " + response.statusCode());
            }
            JsonNode n = mapper.readTree(response.body());
            String category = n.path("category").asText("general");
            Difficulty difficulty;
            try {
                difficulty = Difficulty.valueOf(n.path("difficulty").asText("MEDIUM")
                        .trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                difficulty = Difficulty.MEDIUM;
            }
            double confidence = Math.min(1d, Math.max(0d, n.path("confidence").asDouble(0.5d)));
            return new Decision(category, difficulty, confidence, n.path("reason").asText("外部决策"));
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("外部决策服务调用失败: " + e.getMessage(), e);
        }
    }
}
