package com.tioka.connector.application.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.config.OAuthProviderProperties;
import com.tioka.connector.infrastructure.UserCredentialRepository;
import com.tioka.connector.infrastructure.persistence.UserCredentialDO;
import com.tioka.framework.security.AesGcmCredentialCipher;
import com.tioka.framework.security.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FND-08 默认保管库离线单测（纯 JUnit5 + Mockito，无 Spring / 网络）：
 * 缓存命中 / 5 分钟刷新窗口 / 刷新失败降级 / revoke / 加解密 roundtrip / store upsert。
 */
class DefaultUserTokenVaultTest {

    private static final String USER = "user_1";
    private static final String PROVIDER = "feishu:cli_a1";
    private static final String MASTER_KEY = "test-master-key-not-production";

    private UserCredentialRepository repository;
    private OAuthTokenClient tokenClient;
    private CredentialCipher cipher;
    private OAuthProviderProperties properties;
    private OAuthStateCodec stateCodec;
    private Instant now;
    private DefaultUserTokenVault vault;

    @BeforeEach
    void setUp() {
        repository = mock(UserCredentialRepository.class);
        tokenClient = mock(OAuthTokenClient.class);
        cipher = new AesGcmCredentialCipher(MASTER_KEY, 1);
        properties = new OAuthProviderProperties();
        stateCodec = new OAuthStateCodec(new ObjectMapper(), MASTER_KEY);
        now = Instant.parse("2026-11-10T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        vault = new DefaultUserTokenVault(repository, cipher, tokenClient, properties, stateCodec, clock);
    }

    private OAuthProviderProperties.Provider providerConfig() {
        OAuthProviderProperties.Provider cfg = new OAuthProviderProperties.Provider();
        cfg.setAuthUrl("https://open.feishu.cn/auth");
        cfg.setTokenUrl("https://open.feishu.cn/token");
        cfg.setClientId("cli_a1");
        cfg.setClientSecret("secret-x");
        cfg.setRedirectUri("https://app.example.com/api/connector/oauth/callback");
        cfg.setScopes(List.of("docx:read"));
        return cfg;
    }

    private UserCredentialDO row(String status, LocalDateTime accessExpiresAt, boolean withRefresh) {
        UserCredentialDO cred = new UserCredentialDO();
        cred.setId("cred_01JC0000000000000000TEST");
        cred.setUserId(USER);
        cred.setProvider(PROVIDER);
        cred.setStatus(status);
        cred.setKeyVersion(1);
        cred.setAccessTokenEnc(cipher.encrypt("cached-access-token", cred.getId(), USER));
        if (withRefresh) {
            cred.setRefreshTokenEnc(cipher.encrypt("cached-refresh-token", cred.getId(), USER));
        }
        cred.setAccessExpiresAt(accessExpiresAt);
        cred.setCreateAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        return cred;
    }

    @Test
    @DisplayName("未授权（无行）返回空")
    void noRowReturnsEmpty() {
        when(repository.findByUserAndProvider(USER, PROVIDER)).thenReturn(Optional.empty());
        assertThat(vault.accessToken(USER, PROVIDER)).isEmpty();
        verify(tokenClient, never()).refresh(anyString(), anyString());
    }

    @Test
    @DisplayName("剩余 > 5 分钟：直接返回缓存令牌，不触发刷新")
    void freshTokenServedFromCache() {
        LocalDateTime expires = LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC);
        when(repository.findByUserAndProvider(USER, PROVIDER))
                .thenReturn(Optional.of(row("ACTIVE", expires, true)));

        assertThat(vault.accessToken(USER, PROVIDER)).contains("cached-access-token");
        verify(tokenClient, never()).refresh(anyString(), anyString());
        verify(repository, never()).updateById(any());
    }

    @Test
    @DisplayName("剩余 < 5 分钟：触发刷新成功，返回新令牌并 upsert")
    void expiringTokenTriggersRefresh() {
        LocalDateTime expires = LocalDateTime.ofInstant(now.plusSeconds(120), ZoneOffset.UTC);
        when(repository.findByUserAndProvider(USER, PROVIDER))
                .thenReturn(Optional.of(row("ACTIVE", expires, true)));
        when(tokenClient.refresh(eq(PROVIDER), eq("cached-refresh-token")))
                .thenReturn(new OAuthTokenClient.TokenResponse("new-access", "new-refresh", 7200L, "docx:read"));

        assertThat(vault.accessToken(USER, PROVIDER)).contains("new-access");

        verify(repository).updateById(org.mockito.ArgumentMatchers.argThat(saved -> {
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(saved.getKeyVersion()).isEqualTo(1);
            // 落库的是密文，可解密回明文
            assertThat(cipher.decrypt(saved.getAccessTokenEnc(), saved.getId(), USER)).isEqualTo("new-access");
            assertThat(cipher.decrypt(saved.getRefreshTokenEnc(), saved.getId(), USER)).isEqualTo("new-refresh");
            assertThat(saved.getAccessExpiresAt()).isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(7200), ZoneOffset.UTC));
            return true;
        }));
    }

    @Test
    @DisplayName("刷新失败：置 REFRESH_FAILED 且返回空")
    void refreshFailureMarksRefreshFailed() {
        LocalDateTime expires = LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC);
        when(repository.findByUserAndProvider(USER, PROVIDER))
                .thenReturn(Optional.of(row("ACTIVE", expires, true)));
        when(tokenClient.refresh(anyString(), anyString()))
                .thenThrow(new IllegalStateException("token 端点返回 400"));

        assertThat(vault.accessToken(USER, PROVIDER)).isEmpty();
        verify(repository).updateById(org.mockito.ArgumentMatchers.argThat(saved ->
                "REFRESH_FAILED".equals(saved.getStatus())));
    }

    @Test
    @DisplayName("revoke 后返回空（行保留）")
    void revokedUserGetsNothing() {
        when(repository.findByUserAndProvider(USER, PROVIDER))
                .thenReturn(Optional.of(row("REVOKED", LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC), true)));

        assertThat(vault.accessToken(USER, PROVIDER)).isEmpty();
    }

    @Test
    @DisplayName("revoke 置状态保留行，不物理删除")
    void revokeKeepsRow() {
        UserCredentialDO existing = row("ACTIVE", LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC), true);
        when(repository.findByUserAndProvider(USER, PROVIDER)).thenReturn(Optional.of(existing));

        vault.revoke(USER, PROVIDER);

        verify(repository).updateById(org.mockito.ArgumentMatchers.argThat(saved ->
                "REVOKED".equals(saved.getStatus()) && saved.getId().equals(existing.getId())));
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("加解密 roundtrip：真实 AesGcmCredentialCipher，AAD 绑定 credentialId|userId")
    void cipherRoundtrip() {
        String enc = cipher.encrypt("tok-123", "cred_1", USER);
        assertThat(enc).startsWith("v1:").doesNotContain("tok-123");
        assertThat(cipher.decrypt(enc, "cred_1", USER)).isEqualTo("tok-123");
        // AAD 不匹配必须失败
        assertThatThrownBy(() -> cipher.decrypt(enc, "cred_1", "other-user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("store upsert：新授权 insert，重复授权更新既有行并回 ACTIVE")
    void storeUpserts() {
        // 首次授权：insert（Id 生成 cred_ 前缀，≤32 字符）
        when(repository.findByUserAndProvider(USER, PROVIDER)).thenReturn(Optional.empty());
        vault.store(USER, PROVIDER, "tok-new", "rt-new", 3600L, "docx:read");
        verify(repository).insert(org.mockito.ArgumentMatchers.argThat(saved -> {
            assertThat(saved.getId()).startsWith("cred_").hasSizeLessThanOrEqualTo(32);
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(cipher.decrypt(saved.getAccessTokenEnc(), saved.getId(), USER)).isEqualTo("tok-new");
            return true;
        }));

        // 重复授权：更新既有行（旧行含 refresh token，新响应未下发 → 保留旧值）
        UserCredentialDO existing = row("REFRESH_FAILED", LocalDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC), true);
        when(repository.findByUserAndProvider(USER, PROVIDER)).thenReturn(Optional.of(existing));
        vault.store(USER, PROVIDER, "tok-v2", null, 1800L, "docx:read");
        verify(repository).updateById(org.mockito.ArgumentMatchers.argThat(saved -> {
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(cipher.decrypt(saved.getAccessTokenEnc(), saved.getId(), USER)).isEqualTo("tok-v2");
            // provider 未下发新 refresh token：保留旧值
            assertThat(cipher.decrypt(saved.getRefreshTokenEnc(), saved.getId(), USER)).isEqualTo("cached-refresh-token");
            return true;
        }));
        verify(repository, org.mockito.Mockito.times(1)).insert(any());
    }

    @Test
    @DisplayName("authorizeUrl：标准参数 + 可验签 state；缺配置抛明确异常")
    void authorizeUrlBuildsAndValidatesState() {
        properties.setProviders(Map.of(PROVIDER, providerConfig()));

        String url = vault.authorizeUrl(USER, PROVIDER, "/settings/connect");
        assertThat(url).startsWith("https://open.feishu.cn/auth?");
        assertThat(url).contains("response_type=code", "client_id=cli_a1", "scope=docx%3Aread", "state=");

        // state 回环可验签并还原 userId / provider / returnTo（截取 state= 到下一个 &）
        int stateStart = url.indexOf("state=") + 6;
        int stateEnd = url.indexOf('&', stateStart);
        String encodedState = stateEnd < 0 ? url.substring(stateStart) : url.substring(stateStart, stateEnd);
        String state = java.net.URLDecoder.decode(encodedState, java.nio.charset.StandardCharsets.UTF_8);
        Optional<OAuthStateCodec.StatePayload> decoded = stateCodec.decode(state);
        assertThat(decoded).isPresent();
        assertThat(decoded.get().userId()).isEqualTo(USER);
        assertThat(decoded.get().provider()).isEqualTo(PROVIDER);
        assertThat(decoded.get().returnTo()).isEqualTo("/settings/connect");

        // 篡改 state 验签失败
        assertThat(stateCodec.decode(state + "x")).isEmpty();

        // 缺配置
        properties.setProviders(Map.of());
        assertThatThrownBy(() -> vault.authorizeUrl(USER, "feishu:unknown", "/x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tioka.connector.oauth");
    }

    @Test
    @DisplayName("无 refresh token 且过期：不刷新、直接解密返回（由 provider 侧重授权）")
    void expiredWithoutRefreshTokenReturnsEmpty() {
        // 过期但无 refresh token：不满足刷新前置，仅返回缓存解密值（剩余寿命判断只决定刷新，不拦截解密）
        LocalDateTime expires = LocalDateTime.ofInstant(now.minusSeconds(10), ZoneOffset.UTC);
        when(repository.findByUserAndProvider(USER, PROVIDER))
                .thenReturn(Optional.of(row("ACTIVE", expires, false)));
        assertThat(vault.accessToken(USER, PROVIDER)).contains("cached-access-token");
        verify(tokenClient, never()).refresh(anyString(), anyString());
    }
}
