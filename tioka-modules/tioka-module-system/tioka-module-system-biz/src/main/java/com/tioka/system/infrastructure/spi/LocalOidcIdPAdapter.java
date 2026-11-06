package com.tioka.system.infrastructure.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.system.api.spi.IdPAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * OIDC 联邦：
 * - issuer 以 mock:// 开头：沙箱假联邦（CI）；
 * - 否则：authorization_code → token endpoint（可配或 {issuer}/protocol/openid-connect/token）。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.auth", name = "oidc-enabled", havingValue = "true")
public class LocalOidcIdPAdapter implements IdPAdapter {
    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;
    private final String redirectUri;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public LocalOidcIdPAdapter(@Value("${tioka.auth.oidc-issuer:mock://oidc}") String issuer,
                               @Value("${tioka.auth.oidc-client-id:tioka}") String clientId,
                               @Value("${tioka.auth.oidc-client-secret:}") String clientSecret,
                               @Value("${tioka.auth.oidc-token-url:}") String tokenUrl,
                               @Value("${tioka.auth.oidc-redirect-uri:http://localhost:5173/login}") String redirectUri,
                                  ObjectMapper mapper) {
        this.mapper = mapper;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.tokenUrl = tokenUrl == null ? "" : tokenUrl.trim();
        this.redirectUri = redirectUri;
    }

    @Override
    public String name() {
        return "oidc:" + issuer;
    }

    @Override
    public Optional<FederatedIdentity> authenticate(Map<String, String> credentials) {
        String code = credentials.get("code");
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        if (issuer.startsWith("mock://")) {
            return Optional.of(new FederatedIdentity("oidc-" + code, code + "@example.com", "OIDC User"));
        }
        try {
            return Optional.of(exchangeCode(code, credentials.get("code_verifier")));
        } catch (Exception e) {
            throw new IllegalStateException("OIDC token exchange failed: " + e.getMessage(), e);
        }
    }

    public String authorizationUrl(String state) {
        if (issuer.startsWith("mock://")) {
            return "/api/system/auth/oidc/callback?code=sandbox&state=" + URLEncoder.encode(state == null ? "" : state, StandardCharsets.UTF_8);
        }
        String auth = issuer.replaceAll("/$", "") + "/protocol/openid-connect/auth";
        return auth
                + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope=" + URLEncoder.encode("openid profile email", StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state == null ? "" : state, StandardCharsets.UTF_8);
    }

    private FederatedIdentity exchangeCode(String code, String codeVerifier) throws Exception {
        String endpoint = !tokenUrl.isBlank()
                ? tokenUrl
                : issuer.replaceAll("/$", "") + "/protocol/openid-connect/token";
        Map<String, String> formMap = new java.util.LinkedHashMap<>();
        formMap.put("grant_type", "authorization_code");
        formMap.put("code", code);
        formMap.put("redirect_uri", redirectUri);
        formMap.put("client_id", clientId);
        formMap.put("client_secret", clientSecret);
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            formMap.put("code_verifier", codeVerifier);
        }
        String form = formMap.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
        String idToken = root.path("id_token").asText(null);
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalStateException("missing id_token: " + resp.body());
        }
        JsonNode claims = decodeJwtPayload(idToken);
        String sub = claims.path("sub").asText("unknown");
        String email = claims.path("email").asText(sub + "@oidc.local");
        String name = claims.path("name").asText(claims.path("preferred_username").asText(email));
        return new FederatedIdentity(sub, email, name);
    }

    private JsonNode decodeJwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new IllegalStateException("invalid jwt");
        byte[] json = Base64.getUrlDecoder().decode(parts[1]);
        return mapper.readTree(json);
    }
}
