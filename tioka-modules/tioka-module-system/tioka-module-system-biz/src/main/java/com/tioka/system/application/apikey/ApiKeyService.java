package com.tioka.system.application.apikey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.system.application.WorkspaceService;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysApiKeyRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.persistence.SysApiKeyDO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务账号 API Key 管理与鉴权（FND-06）。
 */
@Service
public class ApiKeyService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration TOUCH_TTL = Duration.ofSeconds(60);

    private final SysApiKeyRepository keys;
    private final AuthUserRepository users;
    private final WorkspaceService workspaces;
    private final PrincipalHolder principals;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ApiKeyService(SysApiKeyRepository keys,
                         AuthUserRepository users,
                         WorkspaceService workspaces,
                         PrincipalHolder principals,
                         StringRedisTemplate redis,
                         ObjectMapper objectMapper) {
        this.keys = keys;
        this.users = users;
        this.workspaces = workspaces;
        this.principals = principals;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreatedKey create(String serviceUserId, String name, String workspaceId,
                             List<String> scopes, Instant expiresAt) {
        requireManageApiKeys(workspaceId);
        if (serviceUserId == null || serviceUserId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "serviceUserId 必填");
        }
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 必填");
        }
        AuthUserDO sa = requireServiceUser(serviceUserId);
        String boundWs = blankToNull(workspaceId);
        if (boundWs != null) {
            workspaces.require(boundWs);
        }
        ApiKeyMaterial.Generated gen = ApiKeyMaterial.generate();
        String id = Ids.newId("ak");
        Instant now = Instant.now();
        String operator = principals.current().userId();
        SysApiKeyDO row = new SysApiKeyDO();
        row.setId(id);
        row.setName(name.trim());
        // 未绑定时先占位，避免 MetaObjectHandler 把当前空间写入；插入后清回 null
        row.setWorkspaceId(boundWs != null ? boundWs : "__unbound__");
        row.setKeyPrefix(gen.keyPrefix());
        row.setKeyHash(gen.keyHash());
        row.setServiceUserId(String.valueOf(sa.getId()));
        row.setScopes(serializeScopes(scopes));
        row.setExpiresAt(expiresAt);
        row.setCreateAt(now);
        row.setUpdateAt(now);
        row.setCreateBy(operator);
        row.setUpdateBy(operator);
        row.setDeleted(false);
        keys.insert(row);
        if (boundWs == null) {
            keys.clearWorkspaceBinding(id);
            row.setWorkspaceId(null);
        }
        cachePut(row);
        return new CreatedKey(id, gen.plaintext());
    }

    public List<KeyView> list() {
        Principal p = principals.current();
        requireAnyManage();
        List<SysApiKeyDO> rows;
        if (p.hasPermission(Permissions.TENANT_ADMIN) || p.hasPermission("*")
                || p.roles().contains("tenant_admin")) {
            rows = keys.listAll();
        } else {
            rows = keys.listByWorkspace(p.workspaceId());
        }
        List<KeyView> out = new ArrayList<>(rows.size());
        for (SysApiKeyDO r : rows) {
            out.add(toView(r));
        }
        return out;
    }

    @Transactional
    public void revoke(String id) {
        SysApiKeyDO row = keys.findById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "API Key 不存在");
        }
        requireManageApiKeys(row.getWorkspaceId());
        keys.softDelete(id);
        redis.delete(cacheKey(row.getKeyHash()));
    }

    /**
     * 校验明文 API Key，返回服务账号与绑定信息；过期 / 无效 → 401。
     */
    public Resolved authenticate(String plaintext) {
        if (!ApiKeyMaterial.looksLikeApiKey(plaintext)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
        }
        String hash = ApiKeyMaterial.sha256Hex(plaintext);
        SysApiKeyDO row = cacheGet(hash);
        if (row == null) {
            row = keys.findByHash(hash);
            if (row != null) {
                cachePut(row);
            }
        }
        if (row == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
        }
        if (row.getExpiresAt() != null && !row.getExpiresAt().isAfter(Instant.now())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "API Key 已过期");
        }
        AuthUserDO sa = users.findById(Long.parseLong(row.getServiceUserId()));
        if (sa == null || !Boolean.TRUE.equals(sa.getEnabled())
                || !"SERVICE".equalsIgnoreCase(sa.getType())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
        }
        touchLastUsed(row);
        return new Resolved(sa.getId(), row.getWorkspaceId(), parseScopes(row.getScopes()), row.getId());
    }

    /** 最终权限 = 角色权限 ∩ scopes（空 scopes 不收窄）。 */
    public Principal applyScopes(Principal base, Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return base;
        }
        Set<String> narrowed = new LinkedHashSet<>();
        for (String perm : base.permissions()) {
            if ("*".equals(perm)) {
                // 通配与 scopes 求交：仅保留 scopes 中的具体权限
                narrowed.addAll(scopes);
            } else if (scopes.contains(perm)) {
                narrowed.add(perm);
            }
        }
        return new Principal(base.userId(), base.workspaceId(),
                base.type() == null ? PrincipalType.SERVICE : base.type(),
                base.roles(), narrowed, base.authMethod());
    }

    private void touchLastUsed(SysApiKeyDO row) {
        String touchKey = "auth:apikey:touch:" + row.getId();
        Boolean first = redis.opsForValue().setIfAbsent(touchKey, "1", TOUCH_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        Instant now = Instant.now();
        keys.touchLastUsed(row.getId(), now);
        row.setLastUsedAt(now);
        cachePut(row);
    }

    private void requireAnyManage() {
        Principal p = principals.current();
        if (p.hasPermission(Permissions.TENANT_ADMIN) || p.hasPermission("*")
                || p.hasPermission(Permissions.WORKSPACE_MANAGE)) {
            return;
        }
        throw new BizException(ErrorCode.FORBIDDEN);
    }

    private void requireManageApiKeys(String workspaceId) {
        Principal p = principals.current();
        if (p.hasPermission(Permissions.TENANT_ADMIN) || p.hasPermission("*")
                || p.roles().contains("tenant_admin")) {
            return;
        }
        if (!p.hasPermission(Permissions.WORKSPACE_MANAGE)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        String target = blankToNull(workspaceId);
        if (target == null) {
            // 无绑定空间的 Key 仅 tenant:admin 可管
            throw new BizException(ErrorCode.FORBIDDEN, "无绑定空间的 API Key 需 tenant:admin");
        }
        if (!target.equals(p.workspaceId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能管理其他空间的 API Key");
        }
    }

    private AuthUserDO requireServiceUser(String serviceUserId) {
        long id;
        try {
            id = Long.parseLong(serviceUserId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "服务账号不存在");
        }
        AuthUserDO row = users.findById(id);
        if (row == null || !"SERVICE".equalsIgnoreCase(row.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "serviceUserId 必须是服务账号");
        }
        if (!Boolean.TRUE.equals(row.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "服务账号已禁用");
        }
        return row;
    }

    private void cachePut(SysApiKeyDO row) {
        try {
            redis.opsForValue().set(cacheKey(row.getKeyHash()), objectMapper.writeValueAsString(row), CACHE_TTL);
        } catch (JsonProcessingException ignored) {
            // 缓存失败不影响主路径
        }
    }

    private SysApiKeyDO cacheGet(String hash) {
        String json = redis.opsForValue().get(cacheKey(hash));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SysApiKeyDO.class);
        } catch (JsonProcessingException e) {
            redis.delete(cacheKey(hash));
            return null;
        }
    }

    private static String cacheKey(String hash) {
        return "auth:apikey:" + hash;
    }

    private String serializeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return null;
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String s : scopes) {
            if (s != null && !s.isBlank()) {
                cleaned.add(s.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(raw, new TypeReference<>() {});
            return new HashSet<>(list);
        } catch (JsonProcessingException e) {
            // 兼容逗号分隔
            Set<String> out = new HashSet<>();
            for (String p : raw.split(",")) {
                if (!p.isBlank()) {
                    out.add(p.trim());
                }
            }
            return out;
        }
    }

    private static KeyView toView(SysApiKeyDO r) {
        return new KeyView(r.getId(), r.getName(), r.getKeyPrefix(),
                r.getLastUsedAt() == null ? null : r.getLastUsedAt().toString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record CreatedKey(String id, String key) {}

    public record KeyView(String id, String name, String prefix, String lastUsedAt) {}

    public record Resolved(long serviceUserId, String workspaceId, Set<String> scopes, String keyId) {}
}
