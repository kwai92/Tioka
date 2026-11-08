package com.tioka.connector.infrastructure;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.ImChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 飞书 IM：始终落 outbox；
 * 1) 配置 app-id/app-secret → OpenAPI tenant_access_token + im/v1/messages；
 * 2) 否则配置 webhook-url → 自定义机器人协议；
 * 3) 否则仅 outbox（CI/本地）。
 */
@Component
public class FeishuImChannelAdapter implements ImChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(FeishuImChannelAdapter.class);
    /** token 提前刷新余量（毫秒）。 */
    private static final long TOKEN_REFRESH_SKEW_MS = 60_000L;
    private final ImOutboxRepository outboxRepo;
    private final ObjectMapper mapper;
    private final EgressClient egress;
    private final String webhookUrl;
    private final String appId;
    private final String appSecret;
    private final String apiBase;
    private final String receiveIdType;
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    public FeishuImChannelAdapter(ImOutboxRepository outboxRepo,
                                  EgressClient egress,
                                  @Value("${tioka.feishu.webhook-url:}") String webhookUrl,
                                  @Value("${tioka.feishu.app-id:}") String appId,
                                  @Value("${tioka.feishu.app-secret:}") String appSecret,
                                  @Value("${tioka.feishu.api-base:https://open.feishu.cn}") String apiBase,
                                  @Value("${tioka.feishu.receive-id-type:chat_id}") String receiveIdType,
                                  ObjectMapper mapper) {
        this.mapper = mapper;
        this.outboxRepo = outboxRepo;
        this.egress = egress;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.apiBase = apiBase == null || apiBase.isBlank() ? "https://open.feishu.cn" : apiBase.replaceAll("/$", "");
        this.receiveIdType = receiveIdType == null || receiveIdType.isBlank() ? "chat_id" : receiveIdType;
    }

    @Override
    public String channelType() {
        return "feishu";
    }

    @Override
    public void sendText(String conversationId, String text) {
        Map<String, Object> msg = Map.of("type", "text", "conversationId", conversationId, "text", text);
        persistAndMaybePost(msg);
    }

    @Override
    public void sendCard(String conversationId, Map<String, Object> card) {
        Map<String, Object> msg = Map.of("type", "card", "conversationId", conversationId, "card", card);
        persistAndMaybePost(msg);
    }

    public List<Map<String, Object>> outbox() {
        return outboxRepo.findByChannel("feishu").stream()
                .<Map<String, Object>>map(r -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = mapper.readValue(r.getPayloadJson(), Map.class);
                        return m;
                    } catch (Exception e) {
                        return Map.of();
                    }
                }).toList();
    }

    private void persistAndMaybePost(Map<String, Object> msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            outboxRepo.insert("feishu", json);
            log.info("[feishu] outbox {}", json);
            if (!appId.isBlank() && !appSecret.isBlank()) {
                postOpenApi(msg);
            } else if (!webhookUrl.isBlank()) {
                postWebhook(msg);
            }
        } catch (Exception e) {
            throw new IllegalStateException("feishu send failed: " + e.getMessage(), e);
        }
    }

    private void postOpenApi(Map<String, Object> msg) throws Exception {
        String token = tenantAccessToken();
        String conversationId = String.valueOf(msg.get("conversationId"));
        String msgType;
        String content;
        if ("text".equals(String.valueOf(msg.get("type")))) {
            msgType = "text";
            content = mapper.writeValueAsString(Map.of("text", String.valueOf(msg.get("text"))));
        } else {
            msgType = "interactive";
            content = mapper.writeValueAsString(msg.get("card"));
        }
        String body = mapper.writeValueAsString(Map.of(
                "receive_id", conversationId,
                "msg_type", msgType,
                "content", content
        ));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/open-apis/im/v1/messages?receive_id_type=" + receiveIdType))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = egress.send(req);
        log.info("[feishu] openapi status={} body={}", resp.statusCode(), resp.body());
        JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("feishu openapi rejected: " + resp.body());
        }
    }

    private String tenantAccessToken() throws Exception {
        CachedToken cached = tokenCache.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAtMs() > now + TOKEN_REFRESH_SKEW_MS) {
            return cached.token();
        }
        String body = mapper.writeValueAsString(Map.of("app_id", appId, "app_secret", appSecret));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/open-apis/auth/v3/tenant_access_token/internal"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = egress.send(req);
        JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("feishu token failed: " + resp.body());
        }
        String token = root.path("tenant_access_token").asText();
        int expireSec = root.path("expire").asInt(7200);
        tokenCache.set(new CachedToken(token, now + expireSec * 1000L));
        return token;
    }

    private void postWebhook(Map<String, Object> msg) throws Exception {
        assertAllowedWebhook(webhookUrl);
        String body;
        if ("text".equals(String.valueOf(msg.get("type")))) {
            body = mapper.writeValueAsString(Map.of(
                    "msg_type", "text",
                    "content", Map.of("text", String.valueOf(msg.get("text")))
            ));
        } else {
            body = mapper.writeValueAsString(Map.of(
                    "msg_type", "interactive",
                    "card", msg.get("card")
            ));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = egress.send(req);
        log.info("[feishu] webhook status={} body={}", resp.statusCode(), resp.body());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("feishu webhook HTTP " + resp.statusCode());
        }
    }

    static void assertAllowedWebhook(String url) {
        URI u = URI.create(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalStateException("feishu webhook must be http(s)");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        boolean ok = host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.endsWith(".feishu.cn")
                || host.equals("feishu.cn")
                || host.endsWith(".larksuite.com")
                || host.equals("larksuite.com")
                || host.endsWith(".feishu.net")
                || host.equals("open.feishu.cn")
                || host.equals("open.larksuite.com");
        if (!ok) {
            throw new IllegalStateException("feishu webhook host not allowed: " + host);
        }
        if ((host.endsWith("feishu.cn") || host.endsWith("larksuite.com") || host.endsWith("feishu.net"))
                && !"https".equals(scheme)
                && !host.equals("localhost")) {
            throw new IllegalStateException("feishu production webhook must use https");
        }
    }

    private record CachedToken(String token, long expireAtMs) {}
}
