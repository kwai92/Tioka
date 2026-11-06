package com.tioka.system.application.oidc;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.config.OidcProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 真实 OIDC 提供方：授权码换令牌 + id_token 校验（签名 / iss / aud / exp / nonce）。
 */
public final class OidcIdPAdapter implements IdPAdapter {
    private final String idp;
    private final OidcProperties.Provider provider;
    private final JwtDecoder idTokenDecoder;
    private final JwtDecoder accessTokenDecoder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OidcIdPAdapter(String idp, OidcProperties.Provider provider) {
        this.idp = idp;
        this.provider = provider;
        String issuer = requireIssuer(provider);
        NimbusJwtDecoder idDec = (NimbusJwtDecoder) JwtDecoders.fromOidcIssuerLocation(issuer);
        idDec.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator(List.of(provider.getClientId())),
                noncePlaceholderValidator()));
        this.idTokenDecoder = idDec;

        NimbusJwtDecoder atDec = (NimbusJwtDecoder) JwtDecoders.fromOidcIssuerLocation(issuer);
        atDec.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        this.accessTokenDecoder = atDec;
    }

    @Override
    public String name() {
        return "oidc:" + idp;
    }

    public String idpKey() {
        return idp;
    }

    public OidcProperties.Provider provider() {
        return provider;
    }

    public JwtDecoder accessTokenDecoder() {
        return accessTokenDecoder;
    }

    public String authorizationUrl(String state, String nonce, String codeChallenge, String redirectUri) {
        String auth = provider.getIssuerUri().replaceAll("/$", "") + "/protocol/openid-connect/auth";
        String scopes = String.join(" ", provider.getScopes() == null || provider.getScopes().isEmpty()
                ? List.of("openid", "profile", "email")
                : provider.getScopes());
        return auth
                + "?client_id=" + enc(provider.getClientId())
                + "&response_type=code"
                + "&scope=" + enc(scopes)
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(codeChallenge)
                + "&code_challenge_method=S256";
    }

    /**
     * credentials: code, code_verifier, redirect_uri, nonce
     */
    @Override
    public Optional<FederatedIdentity> authenticate(Map<String, String> credentials) {
        String code = credentials.get("code");
        String verifier = credentials.get("code_verifier");
        String redirectUri = credentials.get("redirect_uri");
        String expectedNonce = credentials.get("nonce");
        if (code == null || code.isBlank() || verifier == null || redirectUri == null) {
            return Optional.empty();
        }
        try {
            Map<String, String> token = exchangeCode(code, verifier, redirectUri);
            String idToken = token.get("id_token");
            if (idToken == null || idToken.isBlank()) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "OIDC 响应缺少 id_token");
            }
            Jwt jwt = decodeIdToken(idToken, expectedNonce);
            return Optional.of(toIdentity(jwt));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "OIDC 换码失败: " + e.getMessage());
        }
    }

    public Jwt decodeAccessToken(String token) {
        return accessTokenDecoder.decode(token);
    }

    private Jwt decodeIdToken(String idToken, String expectedNonce) {
        Jwt jwt = idTokenDecoder.decode(idToken);
        String nonce = jwt.getClaimAsString("nonce");
        if (expectedNonce == null || expectedNonce.isBlank() || !expectedNonce.equals(nonce)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "id_token nonce 校验失败");
        }
        return jwt;
    }

    private FederatedIdentity toIdentity(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "id_token 缺少 sub");
        }
        String email = jwt.getClaimAsString("email");
        String preferred = jwt.getClaimAsString("preferred_username");
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) {
            name = preferred != null ? preferred : (email != null ? email : sub);
        }
        List<String> groups = extractGroups(jwt);
        return new FederatedIdentity(sub, email, name, preferred, groups);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGroups(Jwt jwt) {
        String claim = provider.getGroupsClaim() == null || provider.getGroupsClaim().isBlank()
                ? "groups" : provider.getGroupsClaim();
        Object raw = jwt.getClaim(claim);
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    private Map<String, String> exchangeCode(String code, String codeVerifier, String redirectUri) throws Exception {
        String endpoint = provider.getIssuerUri().replaceAll("/$", "") + "/protocol/openid-connect/token";
        Map<String, String> formMap = new LinkedHashMap<>();
        formMap.put("grant_type", "authorization_code");
        formMap.put("code", code);
        formMap.put("redirect_uri", redirectUri);
        formMap.put("client_id", provider.getClientId());
        formMap.put("client_secret", provider.getClientSecret() == null ? "" : provider.getClientSecret());
        formMap.put("code_verifier", codeVerifier);
        String form = formMap.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("token endpoint HTTP " + resp.statusCode() + ": " + resp.body());
        }
        // 轻量解析，避免再引依赖；id_token / access_token 为简单 JSON 字符串字段
        return parseTokenJson(resp.body() == null ? "{}" : resp.body());
    }

    private static Map<String, String> parseTokenJson(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : List.of("id_token", "access_token", "token_type", "refresh_token")) {
            String marker = "\"" + key + "\"";
            int i = body.indexOf(marker);
            if (i < 0) {
                continue;
            }
            int colon = body.indexOf(':', i + marker.length());
            int q1 = body.indexOf('"', colon + 1);
            if (q1 < 0) {
                continue;
            }
            int q2 = body.indexOf('"', q1 + 1);
            while (q2 > 0 && body.charAt(q2 - 1) == '\\') {
                q2 = body.indexOf('"', q2 + 1);
            }
            if (q2 > q1) {
                out.put(key, body.substring(q1 + 1, q2));
            }
        }
        return out;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(List<String> audiences) {
        return token -> {
            List<String> aud = token.getAudience();
            if (aud != null) {
                for (String a : audiences) {
                    if (aud.contains(a)) {
                        return OAuth2TokenValidatorResult.success();
                    }
                }
            }
            // 部分 IdP 把 aud 放成单字符串 claim
            Object raw = token.getClaim(JwtClaimNames.AUD);
            if (raw instanceof String s && audiences.contains(s)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid audience", null));
        };
    }

    /** nonce 在 decodeIdToken 二次校验；此处占位使 Decoder 装配完整。 */
    private static OAuth2TokenValidator<Jwt> noncePlaceholderValidator() {
        return token -> OAuth2TokenValidatorResult.success();
    }

    private static String requireIssuer(OidcProperties.Provider provider) {
        if (provider == null || provider.getIssuerUri() == null || provider.getIssuerUri().isBlank()) {
            throw new IllegalStateException("OIDC issuer-uri 未配置");
        }
        return provider.getIssuerUri().replaceAll("/$", "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
