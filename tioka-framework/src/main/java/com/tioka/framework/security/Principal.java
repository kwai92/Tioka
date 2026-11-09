package com.tioka.framework.security;

import java.util.Objects;
import java.util.Set;

/**
 * 请求或后台任务的不可变身份（ADR-015；ADR-029 第 60 条：无 tenantId）。
 *
 * @param userId      用户或服务账号 ID（十进制字符串）
 * @param workspaceId 当前空间 ID
 * @param type        主体类型
 * @param roles       角色编码
 * @param permissions 展开后的权限点
 * @param authMethod  认证方式：password / oidc / api_key / system
 * @param onBehalfOf  MAS-01（ADR-039）：SERVICE 模式代表经办人 userId；全链不变；null = 非代理
 */
public record Principal(
        String userId,
        String workspaceId,
        PrincipalType type,
        Set<String> roles,
        Set<String> permissions,
        String authMethod,
        String onBehalfOf) {

    public Principal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** 兼容旧 6 参口径（onBehalfOf = null）。 */
    public Principal(String userId, String workspaceId, PrincipalType type,
                     Set<String> roles, Set<String> permissions, String authMethod) {
        this(userId, workspaceId, type, roles, permissions, authMethod, null);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }

    /** 去掉权限集后的身份，用于写入 run_as_json（ADR-029 第 6 条）；onBehalfOf 保留。 */
    public Principal identityOnly() {
        return new Principal(userId, workspaceId, type, roles, Set.of(), authMethod, onBehalfOf);
    }
}
