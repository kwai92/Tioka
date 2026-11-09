package com.tioka.connector.api;

import com.tioka.connector.application.oauth.OAuthStateCodec;
import com.tioka.connector.application.oauth.OAuthTokenClient;
import com.tioka.connector.api.oauth.UserTokenVault;
import com.tioka.connector.config.OAuthProviderProperties;
import com.tioka.framework.security.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * OAuth 授权回调（FND-08 / ADR-021）：GET /api/connector/oauth/callback。
 *
 * <p>provider 重定向回此端点：验 state（HMAC + 时效）→ 授权码换 token → 加密落库 →
 * 302 回 {@code returnTo}。不依赖 CON-02 事件订阅链路。
 */
@RestController
public class OAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackController.class);

    private final UserTokenVault vault;
    private final OAuthTokenClient tokenClient;
    private final OAuthStateCodec stateCodec;
    private final OAuthProviderProperties properties;

    public OAuthCallbackController(UserTokenVault vault,
                                   OAuthTokenClient tokenClient,
                                   OAuthStateCodec stateCodec,
                                   OAuthProviderProperties properties) {
        this.vault = vault;
        this.tokenClient = tokenClient;
        this.stateCodec = stateCodec;
        this.properties = properties;
    }

    @PublicApi(reason = "OAuth 授权回调（provider 浏览器重定向，无会话头）")
    @GetMapping("/api/connector/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam("provider") String provider,
                                         @RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error) {
        return stateCodec.decode(state)
                .filter(payload -> provider != null && provider.equals(payload.provider()))
                .map(payload -> handle(payload, code, error))
                .orElseGet(() -> badRequest("state 校验失败"));
    }

    private ResponseEntity<Void> handle(OAuthStateCodec.StatePayload payload, String code, String error) {
        if (error != null && !error.isBlank()) {
            return redirect(payload, "oauth_error=" + enc(error));
        }
        if (code == null || code.isBlank()) {
            return badRequest("缺少授权码 code");
        }
        try {
            OAuthProviderProperties.Provider cfg = properties.require(payload.provider());
            OAuthTokenClient.TokenResponse tokens =
                    tokenClient.exchangeCode(payload.provider(), code, cfg.getRedirectUri());
            vault.store(payload.userId(), payload.provider(), tokens.accessToken(),
                    tokens.refreshToken(), tokens.expiresInSeconds() == null ? 0 : tokens.expiresInSeconds(),
                    tokens.scopes());
        } catch (RuntimeException e) {
            log.warn("OAuth 回调换 token 失败（provider={}）：{}", payload.provider(), e.getMessage());
            return redirect(payload, "oauth_error=exchange_failed");
        }
        return redirect(payload, "oauth_done=1");
    }

    /** 仅允许站内相对路径，防开放重定向；异常落 / 前端参数由前端页处理。 */
    private ResponseEntity<Void> redirect(OAuthStateCodec.StatePayload payload, String query) {
        String returnTo = payload.returnTo();
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//")) {
            return badRequest("returnTo 非法（须为站内相对路径）");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(returnTo + (returnTo.contains("?") ? '&' : '?') + query))
                .build();
    }

    private static ResponseEntity<Void> badRequest(String message) {
        log.warn("OAuth 回调拒绝：{}", message);
        return ResponseEntity.badRequest().build();
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
