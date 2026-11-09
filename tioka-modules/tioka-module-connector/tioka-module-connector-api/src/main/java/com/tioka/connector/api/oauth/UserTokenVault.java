package com.tioka.connector.api.oauth;

import java.util.Optional;

/**
 * 代用户 OAuth 令牌保管（FND-08，ADR-021，施工设计 M2-03）。
 *
 * <p>令牌以 AES-GCM 加密存于 {@code sys_user_credential}；访问令牌剩余不足 5 分钟时自动刷新。
 * 工具在用户未授权时应返回 {@link #authorizeUrl}，不得静默改用应用身份。
 *
 */
public interface UserTokenVault {

    /**
     * @param provider 例 {@code feishu:{appId}}
     * @return 有效访问令牌；未授权、已撤销或刷新失败时为空
     */
    Optional<String> accessToken(String userId, String provider);

    /** 用户完成授权后回到 {@code returnTo}。 */
    String authorizeUrl(String userId, String provider, String returnTo);

    void revoke(String userId, String provider);

    /**
     * 回调 / 刷新后写入令牌（AES-GCM 加密落 {@code sys_user_credential}，upsert 语义）。
     *
     * @param accessToken     明文访问令牌（实现方加密）
     * @param refreshToken    明文刷新令牌，可为 null（保留既有刷新令牌）
     * @param expiresInSeconds 访问令牌有效秒数（自当前时刻起）
     * @param scopes          授权范围，可空格分隔或 null
     */
    void store(String userId, String provider, String accessToken,
               String refreshToken, long expiresInSeconds, String scopes);
}
