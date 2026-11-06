package com.tioka.system.application.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysGroupRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysUserRoleRepository;
import com.tioka.system.infrastructure.dataobject.SysMemberDO;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PrincipalLoader implements PrincipalLoaderApi {
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final AuthUserRepository users;
    private final SysMemberRepository members;
    private final SysUserRoleRepository userRoles;
    private final SysGroupRepository groups;
    private final RolePermissionService rolePermissions;
    private final TokenService tokens;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PrincipalLoader(AuthUserRepository users,
                           SysMemberRepository members,
                           SysUserRoleRepository userRoles,
                           SysGroupRepository groups,
                           RolePermissionService rolePermissions,
                           TokenService tokens,
                           StringRedisTemplate redis,
                           ObjectMapper objectMapper) {
        this.users = users;
        this.members = members;
        this.userRoles = userRoles;
        this.groups = groups;
        this.rolePermissions = rolePermissions;
        this.tokens = tokens;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Principal load(long userId, String workspaceId) {
        return load(userId, workspaceId, "password");
    }

    public Principal load(long userId, String workspaceId, String authMethod) {
        String method = authMethod == null || authMethod.isBlank() ? "password" : authMethod;
        String cacheKey = "auth:principal:" + userId + ":" + workspaceId + ":" + method;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, Principal.class);
            } catch (JsonProcessingException ignored) {
                redis.delete(cacheKey);
            }
        }

        AuthUserDO u = users.findById(userId);
        if (u == null || !Boolean.TRUE.equals(u.getEnabled())) {
            tokens.revokeAll(userId);
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或账号已禁用");
        }
        String uid = String.valueOf(userId);
        List<SysMemberDO> ms = members.findByUserId(uid);
        String ws = workspaceId;
        if (ws == null || ws.isBlank()) {
            ws = u.getDefaultWorkspaceId();
        }
        if (ws == null || ws.isBlank()) {
            ws = ms.isEmpty() ? null : ms.get(0).getWorkspaceId();
        }
        if (ws == null) {
            throw new BizException(ErrorCode.WORKSPACE_FORBIDDEN, "不是该空间成员");
        }
        final String resolvedWs = ws;
        boolean member = ms.stream().anyMatch(m -> resolvedWs.equals(m.getWorkspaceId()));
        // 系统管理员可不在 sys_member，但仍需能进空间做管理；成员校验：空间成员 或 持有 tenant_admin
        List<String> systemRoles = userRoles.rolesOfUser(uid);
        boolean isTenantAdmin = systemRoles.contains("tenant_admin");
        if (!member && !isTenantAdmin) {
            throw new BizException(ErrorCode.WORKSPACE_FORBIDDEN, "不是该空间成员");
        }
        Set<String> roles = ms.stream()
                .filter(m -> resolvedWs.equals(m.getWorkspaceId()))
                .map(SysMemberDO::getRoleCode)
                .collect(Collectors.toCollection(HashSet::new));
        roles.addAll(systemRoles);
        Set<String> perms = rolePermissions.expand(roles);
        // 预热组缓存
        groupIds(uid);
        Principal principal = new Principal(uid, resolvedWs, PrincipalType.USER, roles, perms, method);
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(principal), CACHE_TTL);
        } catch (JsonProcessingException ignored) {
            // 缓存失败不影响主路径
        }
        return principal;
    }

    /** 用户所属组 ID；Redis {@code auth:groups:{uid}}，TTL 60s。 */
    public List<String> groupIds(String userId) {
        String key = "auth:groups:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            } catch (JsonProcessingException ignored) {
                redis.delete(key);
            }
        }
        List<String> ids = groups.groupIdsOfUser(userId);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(ids), CACHE_TTL);
        } catch (JsonProcessingException ignored) {
            // ignore
        }
        return ids;
    }

    public void evictUser(long userId) {
        Set<String> keys = redis.keys("auth:principal:" + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete("auth:groups:" + userId);
    }

    public void evictGroups(String userId) {
        redis.delete("auth:groups:" + userId);
        try {
            evictUser(Long.parseLong(userId));
        } catch (NumberFormatException ignored) {
            // ignore
        }
    }
}
