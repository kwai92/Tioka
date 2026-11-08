package com.tioka.system.application.oidc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.application.auth.TokenService;
import com.tioka.system.config.OidcProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * FND-05：授权码 + PKCE 登录编排（state 存 Redis {@code auth:oidc:{state}}，TTL 10 分钟，回调 GETDEL）。
 */
@Service
public class OidcLoginService {
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OidcProviderRegistry registry;
    private final OidcFederationService federation;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public OidcLoginService(OidcProviderRegistry registry,
                            OidcFederationService federation,
                            StringRedisTemplate redis,
                            ObjectMapper mapper) {
        this.registry = registry;
        this.federation = federation;
        this.redis = redis;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return registry.enabled();
    }

    public String beginAuthorize(String idp, String redirectPath, String callbackAbsoluteUri) {
        if (!registry.enabled()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OIDC 未启用");
        }
        OidcIdPAdapter adapter = (OidcIdPAdapter) registry.requireAdapter(idp);
        String state = randomUrlSafe(16);
        String nonce = randomUrlSafe(16);
        Pkce pkce = Pkce.generate();
        String redirect = normalizeRedirect(redirectPath);
        OidcStatePayload payload = new OidcStatePayload(idp, nonce, pkce.verifier, redirect, callbackAbsoluteUri);
        redis.opsForValue().set(stateKey(state), toJson(payload), STATE_TTL);
        return adapter.authorizationUrl(state, nonce, pkce.challenge, callbackAbsoluteUri);
    }

    public CallbackResult handleCallback(String idp, String code, String state) {
        if (!registry.enabled()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OIDC 未启用");
        }
        if (state == null || state.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 state");
        }
        String key = stateKey(state);
        String raw = redis.opsForValue().getAndDelete(key);
        if (raw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "state 无效或已使用");
        }
        OidcStatePayload payload = fromJson(raw);
        if (!idp.equals(payload.idp())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "state 与 idp 不匹配");
        }
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少 authorization code");
        }
        OidcProperties.Provider provider = registry.requireProvider(idp);
        OidcIdPAdapter adapter = (OidcIdPAdapter) registry.requireAdapter(idp);
        Map<String, String> creds = Map.of(
                "code", code,
                "code_verifier", payload.codeVerifier(),
                "redirect_uri", payload.callbackUri(),
                "nonce", payload.nonce());
        Optional<IdPAdapter.FederatedIdentity> identity = adapter.authenticate(creds);
        if (identity.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "OIDC 认证失败");
        }
        TokenService.TokenPair pair = federation.federateAndIssue(idp, provider, identity.get());
        String frontend = registry.properties().getFrontendBaseUrl();
        if (frontend == null || frontend.isBlank()) {
            frontend = "http://127.0.0.1:5173";
        }
        String loc = frontend.replaceAll("/$", "")
                + "/login/callback?redirect=" + URLEncoder.encode(payload.redirect(), StandardCharsets.UTF_8);
        return new CallbackResult(pair, loc);
    }

    private static String normalizeRedirect(String redirectPath) {
        if (redirectPath == null || redirectPath.isBlank()) {
            return "/";
        }
        return redirectPath.startsWith("/") ? redirectPath : "/" + redirectPath;
    }

    private static String stateKey(String state) {
        return "auth:oidc:" + state;
    }

    private String toJson(OidcStatePayload p) {
        try {
            return mapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private OidcStatePayload fromJson(String raw) {
        try {
            return mapper.readValue(raw, OidcStatePayload.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "state 载荷损坏");
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public record CallbackResult(TokenService.TokenPair tokens, String frontendRedirect) {}

    public record OidcStatePayload(String idp, String nonce, String codeVerifier, String redirect, String callbackUri) {}

    private record Pkce(String verifier, String challenge) {
        static Pkce generate() {
            try {
                String verifier = randomUrlSafe(32);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] dig = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(dig);
                return new Pkce(verifier, challenge);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
