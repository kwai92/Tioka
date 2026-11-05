package com.tioka.framework.security;

import java.util.Objects;
import java.util.Set;

/**
 * 请求或后台任务的不可变身份（ADR-015）。
 *
 * <p>异步任务把 Principal 序列化到任务行的 {@code run_as_json}；按 ADR-029 第 6 条，
 * 持久化时只保存身份字段，{@code permissions} 在每个 Turn 开始和每次工具调用前重新加载。
 *
 * @param tenantId    租户 ID
 * @param userId      用户或服务账号 ID
 * @param workspaceId 当前空间 ID
 * @param type        主体类型
 * @param roles       角色编码
 * @param permissions 展开后的权限点，格式 {@code 资源:动作}，如 {@code agent:write}
 * @param authMethod  认证方式：password / oidc / api_key / system
 */
public record Principal(
        String tenantId,
        String userId,
        String workspaceId,
        PrincipalType type,
        Set<String> roles,
        Set<String> permissions,
        String authMethod) {

    public Principal {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /** 去掉权限集后的身份，用于写入 run_as_json（ADR-029 第 6 条）。 */
    public Principal identityOnly() {
        return new Principal(tenantId, userId, workspaceId, type, roles, Set.of(), authMethod);
    }
}
