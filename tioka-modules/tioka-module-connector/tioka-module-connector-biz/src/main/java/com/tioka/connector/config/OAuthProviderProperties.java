package com.tioka.connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FND-08：{@code tioka.connector.oauth.<provider>.*}（代用户令牌保管，ADR-021 / M2-03）。
 *
 * <p>key 为 provider 标识（例 {@code feishu:cli_xxx}，与 {@code UserTokenVault#authorizeUrl} 的
 * provider 参数一致）。缺失配置时 {@code authorizeUrl} 抛出带明确信息的异常。
 */
@ConfigurationProperties(prefix = "tioka.connector.oauth")
public class OAuthProviderProperties {

    private Map<String, Provider> providers = new LinkedHashMap<>();

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    /** @throws IllegalStateException provider 未配置时给出可行动的配置提示 */
    public Provider require(String provider) {
        Provider p = providers.get(provider);
        if (p == null || p.getAuthUrl() == null || p.getAuthUrl().isBlank()) {
            throw new IllegalStateException(
                    "代用户令牌保管未配置 provider '" + provider + "'：请设置 tioka.connector.oauth."
                            + provider + ".{auth-url,token-url,client-id,client-secret,redirect-uri}（FND-08）");
        }
        return p;
    }

    public static class Provider {
        private String authUrl;
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private List<String> scopes = List.of();

        public String getAuthUrl() {
            return authUrl;
        }

        public void setAuthUrl(String authUrl) {
            this.authUrl = authUrl;
        }

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes == null ? List.of() : scopes;
        }
    }
}
