package com.tioka.connector.application.oauth;

import com.tioka.connector.api.oauth.UserTokenVault;
import com.tioka.connector.config.OAuthProviderProperties;
import com.tioka.connector.infrastructure.UserCredentialRepository;
import com.tioka.connector.infrastructure.persistence.UserCredentialDO;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link UserTokenVault} 默认实现（FND-08 / ADR-021 / M2-03）。
 *
 * <p>令牌 AES-GCM 加密存 {@code sys_user_credential}（AAD = credentialId|userId）；
 * access 剩余不足 5 分钟且有 refresh token 时自动刷新，失败置 {@code REFRESH_FAILED} 返回空；
 * {@code revoke} 置 {@code REVOKED} 保留行。TIMESTAMPTZ 按 UTC 语义读写（ENG-11）。
 */
@Service
public class DefaultUserTokenVault implements UserTokenVault {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserTokenVault.class);
    /** 访问令牌剩余寿命低于该值即触发刷新。 */
    static final long REFRESH_AHEAD_SECONDS = 5 * 60;

    private final UserCredentialRepository repository;
    private final CredentialCipher cipher;
    private final OAuthTokenClient tokenClient;
    private final OAuthProviderProperties properties;
    private final OAuthStateCodec stateCodec;
    private final Clock clock;

    @Autowired
    public DefaultUserTokenVault(UserCredentialRepository repository,
                                 CredentialCipher cipher,
                                 OAuthTokenClient tokenClient,
                                 OAuthProviderProperties properties,
                                 OAuthStateCodec stateCodec) {
        this(repository, cipher, tokenClient, properties, stateCodec, Clock.systemUTC());
    }

    DefaultUserTokenVault(UserCredentialRepository repository,
                          CredentialCipher cipher,
                          OAuthTokenClient tokenClient,
                          OAuthProviderProperties properties,
                          OAuthStateCodec stateCodec,
                          Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.tokenClient = tokenClient;
        this.properties = properties;
        this.stateCodec = stateCodec;
        this.clock = clock;
    }

    @Override
    public Optional<String> accessToken(String userId, String provider) {
        Optional<UserCredentialDO> row = repository.findByUserAndProvider(userId, provider);
        if (row.isEmpty() || !"ACTIVE".equals(row.get().getStatus())) {
            return Optional.empty();
        }
        UserCredentialDO cred = row.get();
        if (needsRefresh(cred)) {
            return refreshAndReturn(cred);
        }
        return Optional.of(cipher.decrypt(cred.getAccessTokenEnc(), cred.getId(), userId));
    }

    @Override
    public String authorizeUrl(String userId, String provider, String returnTo) {
        OAuthProviderProperties.Provider cfg = properties.require(provider);
        String state = stateCodec.encode(userId, provider, returnTo);
        StringBuilder url = new StringBuilder(cfg.getAuthUrl());
        url.append(cfg.getAuthUrl().contains("?") ? '&' : '?')
                .append("response_type=code")
                .append("&client_id=").append(enc(cfg.getClientId()))
                .append("&redirect_uri=").append(enc(cfg.getRedirectUri()))
                .append("&state=").append(enc(state));
        if (cfg.getScopes() != null && !cfg.getScopes().isEmpty()) {
            url.append("&scope=").append(enc(String.join(" ", cfg.getScopes())));
        }
        return url.toString();
    }

    @Override
    public void revoke(String userId, String provider) {
        repository.findByUserAndProvider(userId, provider).ifPresent(cred -> {
            cred.setStatus("REVOKED");
            repository.updateById(cred);
        });
    }

    @Override
    public void store(String userId, String provider, String accessToken,
                      String refreshToken, long expiresInSeconds, String scopes) {
        UserCredentialDO cred = repository.findByUserAndProvider(userId, provider).orElseGet(() -> {
            UserCredentialDO fresh = new UserCredentialDO();
            fresh.setId(Ids.newId("cred"));
            fresh.setUserId(userId);
            fresh.setProvider(provider);
            return fresh;
        });
        applyTokens(cred, accessToken, refreshToken, expiresInSeconds, scopes);
        if (cred.getCreateAt() == null) {
            repository.insert(cred);
        } else {
            repository.updateById(cred);
        }
    }

    /* ---------- 内部 ---------- */

    private boolean needsRefresh(UserCredentialDO cred) {
        return cred.getAccessExpiresAt() != null
                && cred.getRefreshTokenEnc() != null
                && Duration.between(nowUtc(), cred.getAccessExpiresAt()).getSeconds() < REFRESH_AHEAD_SECONDS;
    }

    private Optional<String> refreshAndReturn(UserCredentialDO cred) {
        try {
            String refreshToken = cipher.decrypt(cred.getRefreshTokenEnc(), cred.getId(), cred.getUserId());
            OAuthTokenClient.TokenResponse resp = tokenClient.refresh(cred.getProvider(), refreshToken);
            if (resp.accessToken() == null) {
                throw new IllegalStateException("token 端点未返回 access_token");
            }
            applyTokens(cred, resp.accessToken(), resp.refreshToken(),
                    resp.expiresInSeconds() == null ? 0 : resp.expiresInSeconds(), resp.scopes());
            repository.updateById(cred);
            return Optional.of(resp.accessToken());
        } catch (RuntimeException e) {
            log.warn("代用户令牌刷新失败（provider={}, user={}）：{}",
                    cred.getProvider(), cred.getUserId(), e.getMessage());
            cred.setStatus("REFRESH_FAILED");
            repository.updateById(cred);
            return Optional.empty();
        }
    }

    /** upsert 令牌字段（加密落库；key_version 按当前 cipher；provider 未下发新 refresh token 时保留旧值）。 */
    private void applyTokens(UserCredentialDO cred, String accessToken, String refreshToken,
                             long expiresInSeconds, String scopes) {
        cred.setAccessTokenEnc(cipher.encrypt(accessToken, cred.getId(), cred.getUserId()));
        if (refreshToken != null) {
            cred.setRefreshTokenEnc(cipher.encrypt(refreshToken, cred.getId(), cred.getUserId()));
        }
        cred.setAccessExpiresAt(expiresInSeconds > 0
                ? nowUtc().plusSeconds(expiresInSeconds)
                : nowUtc().plusSeconds(REFRESH_AHEAD_SECONDS));
        if (scopes != null && !scopes.isBlank()) {
            cred.setScopes(scopes.length() > 1024 ? scopes.substring(0, 1024) : scopes);
        }
        cred.setKeyVersion(cipher.currentKeyVersion());
        cred.setStatus("ACTIVE");
    }

    /** UTC 语义当前时刻（与 UtcLocalDateTimeTypeHandler 存储口径一致）。 */
    private LocalDateTime nowUtc() {
        return LocalDateTime.now(clock);
    }

    private static String enc(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
