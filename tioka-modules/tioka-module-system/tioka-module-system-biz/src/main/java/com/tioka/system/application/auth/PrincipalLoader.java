package com.tioka.system.application.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
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
    private final RolePermissionService rolePermissions;
    private final TokenService tokens;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PrincipalLoader(AuthUserRepository users,
                           SysMemberRepository members,
                           RolePermissionService rolePermissions,
                           TokenService tokens,
                           StringRedisTemplate redis,
                           ObjectMapper objectMapper) {
        this.users = users;
        this.members = members;
        this.rolePermissions = rolePermissions;
        this.tokens = tokens;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Principal load(long userId, String workspaceId) {
        String cacheKey = "auth:principal:" + userId + ":" + workspaceId;
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
        List<SysMemberDO> ms = members.findByUserId(String.valueOf(userId));
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
        if (!member) {
            throw new BizException(ErrorCode.WORKSPACE_FORBIDDEN, "不是该空间成员");
        }
        Set<String> roles = ms.stream()
                .filter(m -> resolvedWs.equals(m.getWorkspaceId()) || "tenant_admin".equals(m.getRoleCode()))
                .map(SysMemberDO::getRoleCode)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> perms = rolePermissions.expand(roles);
        Principal principal = new Principal(String.valueOf(userId), resolvedWs, PrincipalType.USER, roles, perms, "password");
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(principal), CACHE_TTL);
        } catch (JsonProcessingException ignored) {
            // 缓存失败不影响主路径
        }
        return principal;
    }

    public void evictUser(long userId) {
        Set<String> keys = redis.keys("auth:principal:" + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
