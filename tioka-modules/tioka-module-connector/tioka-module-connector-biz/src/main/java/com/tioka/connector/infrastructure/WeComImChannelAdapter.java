package com.tioka.connector.infrastructure;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.system.api.spi.ImChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 企业微信群机器人：outbox + 可选 webhook。 */
@Component
public class WeComImChannelAdapter implements ImChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(WeComImChannelAdapter.class);
    private final ImOutboxRepository outbox;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String webhookUrl;

    public WeComImChannelAdapter(ImOutboxRepository outbox,
                                 @Value("${tioka.wecom.webhook-url:}") String webhookUrl,
                                  ObjectMapper mapper) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    @Override
    public String channelType() {
        return "wecom";
    }

    @Override
    public void sendText(String conversationId, String text) {
        persistAndMaybePost(Map.of("type", "text", "conversationId", conversationId, "text", text));
    }

    @Override
    public void sendCard(String conversationId, Map<String, Object> card) {
        persistAndMaybePost(Map.of("type", "card", "conversationId", conversationId, "card", card));
    }

    public List<Map<String, Object>> outbox() {
        return outbox.findByChannel("wecom").stream().map(r -> read(r.getPayloadJson())).toList();
    }

    private void persistAndMaybePost(Map<String, Object> msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            outbox.insert("wecom", json);
            if (!webhookUrl.isBlank()) {
                assertAllowed(webhookUrl);
                String body = mapper.writeValueAsString(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", String.valueOf(msg.getOrDefault("text", msg)))
                ));
                HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(), HttpResponse.BodyHandlers.ofString());
                log.info("[wecom] webhook status={}", resp.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException("wecom send failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> read(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(json, Map.class);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    static void assertAllowed(String url) {
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean ok = host.equals("localhost") || host.equals("127.0.0.1")
                || host.endsWith(".qq.com") || host.contains("qyapi.weixin.qq.com");
        if (!ok || (!"https".equals(scheme) && !"http".equals(scheme))) {
            throw new IllegalStateException("wecom webhook host not allowed: " + host);
        }
    }
}
