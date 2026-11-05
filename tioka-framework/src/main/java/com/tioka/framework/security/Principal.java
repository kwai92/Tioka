package com.tioka.framework.security;

import java.util.Objects;
import java.util.Set;

/**
 * 请求或后台任务的不可变身份（ADR-015；ADR-029 第 36 条：无 tenantId）。
 *
 * @param userId      用户或服务账号 ID（十进制字符串）
 * @param workspaceId 当前空间 ID
 * @param type        主体类型
 * @param roles       角色编码
 * @param permissions 展开后的权限点
 * @param authMethod  认证方式：password / oidc / api_key / system
 */
public record Principal(
        String userId,
        String workspaceId,
        PrincipalType type,
        Set<String> roles,
        Set<String> permissions,
        String authMethod) {

    public Principal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }

    /** 去掉权限集后的身份，用于写入 run_as_json（ADR-029 第 6 条）。 */
    public Principal identityOnly() {
        return new Principal(userId, workspaceId, type, roles, Set.of(), authMethod);
    }
}
