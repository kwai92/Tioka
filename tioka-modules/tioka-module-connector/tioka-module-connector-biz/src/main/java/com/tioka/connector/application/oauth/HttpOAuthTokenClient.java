package com.tioka.connector.application.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.config.OAuthProviderProperties;
import com.tioka.system.api.spi.EgressClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * FND-08 默认令牌端点客户端：标准 OAuth2 {@code application/x-www-form-urlencoded}
 * 授权码 / 刷新请求（RFC 6749 §4.1.3 / §6），JSON 响应取
 * {@code access_token / refresh_token / expires_in / scope}。
 *
 * <p>联测真实 provider 归 CI IT（见批次文档未覆盖项）；非 2xx 抛 IllegalStateException
 * 交由调用方降级（刷新失败 → REFRESH_FAILED）。
 */
@Component
public class HttpOAuthTokenClient implements OAuthTokenClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOAuthTokenClient.class);

    private final EgressClient egress;
    private final ObjectMapper mapper;
    private final OAuthProviderProperties properties;

    public HttpOAuthTokenClient(EgressClient egress, ObjectMapper mapper, OAuthProviderProperties properties) {
        this.egress = egress;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public TokenResponse exchangeCode(String provider, String code, String redirectUri) {
        OAuthProviderProperties.Provider cfg = properties.require(provider);
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(cfg.getClientId())
                + "&client_secret=" + enc(cfg.getClientSecret());
        return post(cfg.getTokenUrl(), form);
    }

    @Override
    public TokenResponse refresh(String provider, String refreshToken) {
        OAuthProviderProperties.Provider cfg = properties.require(provider);
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&client_id=" + enc(cfg.getClientId())
                + "&client_secret=" + enc(cfg.getClientSecret());
        return post(cfg.getTokenUrl(), form);
    }

    private TokenResponse post(String tokenUrl, String form) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = egress.send(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("token 端点返回 " + response.statusCode());
            }
            JsonNode node = mapper.readTree(response.body());
            return new TokenResponse(
                    node.path("access_token").asText(null),
                    node.path("refresh_token").asText(null),
                    node.hasNonNull("expires_in") ? node.get("expires_in").asLong() : null,
                    node.path("scope").asText(null));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OAuth token 端点调用失败：{}", e.getMessage());
            throw new IllegalStateException("token 端点调用失败", e);
        }
    }

    private static String enc(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
