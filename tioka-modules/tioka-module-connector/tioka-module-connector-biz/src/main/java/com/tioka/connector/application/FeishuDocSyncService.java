package com.tioka.connector.application;

import com.tioka.framework.id.Ids;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.spi.FeishuDocContentSpi;
import com.tioka.connector.infrastructure.FeishuDocRepository;
import com.tioka.connector.infrastructure.persistence.FeishuDocMapDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 飞书文档同步：默认本地镜像表；配置 app-id/secret 后：
 * - pull → GET /open-apis/docx/v1/documents/{id}/raw_content
 * - push → 本地镜像 +（可选）POST 创建空文档占位（官方 create 不支持正文）
 */
@Service
public class FeishuDocSyncService implements FeishuDocContentSpi {
    private static final Logger log = LoggerFactory.getLogger(FeishuDocSyncService.class);
    private final FeishuDocRepository docs;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String appId;
    private final String appSecret;
    private final String apiBase;
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    public FeishuDocSyncService(FeishuDocRepository docs,
                                @Value("${tioka.feishu.app-id:}") String appId,
                                @Value("${tioka.feishu.app-secret:}") String appSecret,
                                @Value("${tioka.feishu.api-base:https://open.feishu.cn}") String apiBase,
                                  ObjectMapper mapper) {
        this.mapper = mapper;
        this.docs = docs;
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.apiBase = apiBase == null || apiBase.isBlank() ? "https://open.feishu.cn" : apiBase.replaceAll("/$", "");
    }

    public Mapping map(String localDocId, String feishuDocToken) {
        String id = Ids.newId("map");
        Mapping m = new Mapping(id, localDocId, feishuDocToken);
        FeishuDocMapDO row = new FeishuDocMapDO();
        row.setId(m.id());
        row.setLocalDocId(m.localDocId());
        row.setFeishuDocToken(m.feishuDocToken());
        docs.insertMap(row);
        return m;
    }

    public String push(String mappingId, String content) {
        Mapping m = require(mappingId);
        String remote = "feishu://" + m.feishuDocToken();
        upsertPush(remote, content == null ? "" : content);
        if (live()) {
            try {
                String title = firstLine(content);
                createDocumentPlaceholder(title);
                log.info("[feishu-doc] openapi create placeholder title={}", title);
            } catch (Exception e) {
                log.warn("[feishu-doc] openapi push skipped: {}", e.getMessage());
            }
        }
        return remote;
    }

    public String pull(String mappingId) {
        Mapping m = require(mappingId);
        return pullByToken(m.feishuDocToken());
    }

    /** SPI：按飞书 doc token 取 raw content（OpenAPI 或本地镜像）。 */
    @Override
    public String rawContent(String feishuDocToken) {
        return pullByToken(feishuDocToken);
    }

    private String pullByToken(String feishuDocToken) {
        String token = feishuDocToken == null ? "" : feishuDocToken.trim();
        String remote = "feishu://" + token;
        if (live() && !token.isBlank() && !token.startsWith("local")) {
            try {
                String text = fetchRawContent(token);
                upsertPush(remote, text);
                return text;
            } catch (Exception e) {
                log.warn("[feishu-doc] openapi pull fallback local: {}", e.getMessage());
            }
        }
        String contentLocal = docs.findPushContent(remote);
        return contentLocal == null ? "" : contentLocal;
    }

    private void upsertPush(String remote, String content) {
        docs.upsertPush(remote, content);
    }

    private boolean live() {
        return !appId.isBlank() && !appSecret.isBlank();
    }

    private void createDocumentPlaceholder(String title) throws Exception {
        String body = mapper.writeValueAsString(Map.of("title", title == null || title.isBlank() ? "Tioka Sync" : title));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/open-apis/docx/v1/documents"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + tenantAccessToken())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(resp.body());
        }
    }

    private String fetchRawContent(String documentId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/open-apis/docx/v1/documents/" + documentId + "/raw_content"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + tenantAccessToken())
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(resp.body());
        }
        return root.path("data").path("content").asText("");
    }

    private String tenantAccessToken() throws Exception {
        CachedToken cached = tokenCache.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAtMs() > now + 60_000) {
            return cached.token();
        }
        String body = mapper.writeValueAsString(Map.of("app_id", appId, "app_secret", appSecret));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/open-apis/auth/v3/tenant_access_token/internal"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("feishu token failed: " + resp.body());
        }
        String token = root.path("tenant_access_token").asText();
        int expireSec = root.path("expire").asInt(7200);
        tokenCache.set(new CachedToken(token, now + expireSec * 1000L));
        return token;
    }

    private Mapping require(String mappingId) {
        FeishuDocMapDO row = docs.findMap(mappingId);
        return new Mapping(row.getId(), row.getLocalDocId(), row.getFeishuDocToken());
    }

    private static String firstLine(String content) {
        if (content == null || content.isBlank()) return "Tioka Sync";
        int n = content.indexOf('\n');
        String line = n < 0 ? content : content.substring(0, n);
        return line.length() > 80 ? line.substring(0, 80) : line;
    }

    private record CachedToken(String token, long expireAtMs) {}
    public record Mapping(String id, String localDocId, String feishuDocToken) {}
}
