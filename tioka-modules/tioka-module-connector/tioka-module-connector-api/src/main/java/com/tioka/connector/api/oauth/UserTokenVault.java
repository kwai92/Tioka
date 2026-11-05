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
}
