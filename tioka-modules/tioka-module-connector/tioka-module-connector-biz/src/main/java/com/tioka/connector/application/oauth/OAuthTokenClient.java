package com.tioka.connector.application.oauth;

/**
 * FND-08：OAuth 令牌端点客户端（授权码换 token / refresh token 刷新）。
 *
 * <p>可替换接口：默认 HTTP 实现见 {@link HttpOAuthTokenClient}；测试 mock 本接口。
 */
public interface OAuthTokenClient {

    /** 授权码换令牌（回调端点使用）。 */
    TokenResponse exchangeCode(String provider, String code, String redirectUri);

    /** 刷新令牌（access 剩余不足 5 分钟时使用）。 */
    TokenResponse refresh(String provider, String refreshToken);

    /**
     * @param accessToken  明文访问令牌
     * @param refreshToken 新刷新令牌，provider 未下发时为 null（保留旧值）
     * @param expiresInSeconds 访问令牌有效秒数，端点未返回时为 null
     */
    record TokenResponse(String accessToken, String refreshToken, Long expiresInSeconds, String scopes) {
    }
}
