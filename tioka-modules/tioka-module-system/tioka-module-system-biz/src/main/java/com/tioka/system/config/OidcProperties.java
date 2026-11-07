package com.tioka.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FND-05：{@code tioka.auth.oidc.*}（授权码 + PKCE / JWT 资源服务器）。
 */
@ConfigurationProperties(prefix = "tioka.auth.oidc")
public class OidcProperties {
    private Map<String, Provider> providers = new LinkedHashMap<>();
    private ResourceServer resourceServer = new ResourceServer();
    /** 回调成功后 302 的前端源（含协议与端口），路径固定为 {@code /login/callback}。 */
    private String frontendBaseUrl = "http://127.0.0.1:5173";

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    public ResourceServer getResourceServer() {
        return resourceServer;
    }

    public void setResourceServer(ResourceServer resourceServer) {
        this.resourceServer = resourceServer == null ? new ResourceServer() : resourceServer;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public boolean hasProviders() {
        return providers != null && !providers.isEmpty();
    }

    public static class Provider {
        private String issuerUri;
        private String clientId;
        private String clientSecret;
        private List<String> scopes = List.of("openid", "profile", "email");
        private String groupsClaim = "groups";
        private boolean autoProvision = true;
        private List<GroupRoleMapping> groupRoleMappings = new ArrayList<>();

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
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

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes == null ? List.of() : scopes;
        }

        public String getGroupsClaim() {
            return groupsClaim;
        }

        public void setGroupsClaim(String groupsClaim) {
            this.groupsClaim = groupsClaim;
        }

        public boolean isAutoProvision() {
            return autoProvision;
        }

        public void setAutoProvision(boolean autoProvision) {
            this.autoProvision = autoProvision;
        }

        public List<GroupRoleMapping> getGroupRoleMappings() {
            return groupRoleMappings;
        }

        public void setGroupRoleMappings(List<GroupRoleMapping> groupRoleMappings) {
            this.groupRoleMappings = groupRoleMappings == null ? new ArrayList<>() : groupRoleMappings;
        }
    }

    public static class GroupRoleMapping {
        private String group;
        /** 系统级角色（写入 {@code sys_user_role}），如 {@code tenant_admin}。 */
        private String tenantRole;
        private String workspace;
        private String role;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getTenantRole() {
            return tenantRole;
        }

        public void setTenantRole(String tenantRole) {
            this.tenantRole = tenantRole;
        }

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    public static class ResourceServer {
        private boolean enabled = false;
        private List<String> audiences = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAudiences() {
            return audiences;
        }

        public void setAudiences(List<String> audiences) {
            this.audiences = audiences == null ? new ArrayList<>() : audiences;
        }
    }
}
