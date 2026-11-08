package com.tioka.connector.infrastructure.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.infrastructure.ConnectorSecretBridge;
import com.tioka.connector.infrastructure.persistence.ImBindingDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.EgressClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书 client：tenant_access_token 进程内缓存（提前 60s 过期）。
 */
@Component
public class DefaultFeishuClient implements FeishuClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long SKEW_SECONDS = 60L;

    private final EgressClient egress;
    private final ConnectorSecretBridge secrets;
    private final ObjectMapper mapper;
    private final String apiBase;
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public DefaultFeishuClient(EgressClient egress,
                               ConnectorSecretBridge secrets,
                               ObjectMapper mapper,
                               @Value("${tioka.im.feishu.api-base:https://open.feishu.cn}") String apiBase) {
        this.egress = egress;
        this.secrets = secrets;
        this.mapper = mapper;
        this.apiBase = trimTrailingSlash(apiBase == null || apiBase.isBlank()
                ? "https://open.feishu.cn" : apiBase.trim());
    }

    @Override
    public String tenantAccessToken(ImBindingDO binding) {
        Objects.requireNonNull(binding, "binding");
        String cacheKey = binding.getId() == null ? binding.getAppId() : binding.getId();
        CachedToken cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(SKEW_SECONDS))) {
            return cached.token();
        }
        String appSecret = secrets.resolveOrNull(binding.getSecretRef());
        if (appSecret == null || appSecret.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "应用密钥缺失");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "app_id", binding.getAppId(),
                    "app_secret", appSecret));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = egress.send(request);
            JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || root.path("code").asInt(-1) != 0) {
                throw new BizException(ErrorCode.DEPENDENCY,
                        "获取 tenant_access_token 失败: HTTP " + resp.statusCode()
                                + " code=" + root.path("code").asInt()
                                + " msg=" + root.path("msg").asText(""));
            }
            String token = root.path("tenant_access_token").asText("");
            if (token.isBlank()) {
                throw new BizException(ErrorCode.DEPENDENCY, "未返回 tenant_access_token");
            }
            long expire = root.path("expire").asLong(7200L);
            cache.put(cacheKey, new CachedToken(token, Instant.now().plusSeconds(Math.max(60L, expire))));
            return token;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "获取 tenant_access_token 失败: " + e.getMessage());
        }
    }

    private static String trimTrailingSlash(String s) {
        if (s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
