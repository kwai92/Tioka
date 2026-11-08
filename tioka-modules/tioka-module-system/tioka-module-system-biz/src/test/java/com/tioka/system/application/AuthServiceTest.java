package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.application.auth.PasswordService;
import com.tioka.system.application.auth.RolePermissionService;
import com.tioka.system.application.auth.TokenService;
import com.tioka.system.config.AuthProperties;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysUserRoleRepository;
import com.tioka.system.infrastructure.SysWorkspaceRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private AuthService svc;
    private AuthUserRepository users;
    private PasswordService passwords;
    private Map<Long, AuthUserDO> byId;

    @BeforeEach
    void setUp() {
        byId = new ConcurrentHashMap<>();
        Map<String, AuthUserDO> byName = new ConcurrentHashMap<>();
        AtomicLong seq = new AtomicLong(1000);
        users = new AuthUserRepository() {
            @Override
            public AuthUserDO findByUsername(String username) {
                return byName.get(username);
            }

            @Override
            public AuthUserDO findById(Long id) {
                return byId.get(id);
            }

            @Override
            public AuthUserDO findByIdpAndSubject(String idp, String externalSubject) {
                return byId.values().stream()
                        .filter(u -> idp.equals(u.getIdp()) && externalSubject.equals(u.getExternalSubject()))
                        .findFirst().orElse(null);
            }

            @Override
            public void insert(AuthUserDO user) {
                byId.put(user.getId(), user);
                byName.put(user.getUsername(), user);
            }

            @Override
            public void update(AuthUserDO user) {
                byId.put(user.getId(), user);
                byName.put(user.getUsername(), user);
            }

            @Override
            public long nextId() {
                return seq.incrementAndGet();
            }

            @Override
            public java.util.List<AuthUserDO> search(String q, int offset, int limit) {
                return byId.values().stream().skip(offset).limit(limit).toList();
            }

            @Override
            public long countSearch(String q) {
                return byId.size();
            }
        };
        passwords = new PasswordService();
        AuthUserDO admin = new AuthUserDO();
        admin.setId(1001L);
        admin.setUsername("alice");
        admin.setPasswordHash(passwords.hash("GoodPass!12"));
        admin.setDisplayName("Alice");
        admin.setEnabled(true);
        admin.setMustChangePassword(false);
        users.insert(admin);

        TokenService tokens = mock(TokenService.class);
        when(tokens.issue(anyLong())).thenAnswer(inv ->
                new TokenService.TokenPair("tka_at", "tkr_rt", Instant.now().plusSeconds(3600)));

        SysMemberRepository members = mock(SysMemberRepository.class);
        when(members.findByUserId(anyString())).thenReturn(List.of());
        SysWorkspaceRepository workspaces = mock(SysWorkspaceRepository.class);
        SysUserRoleRepository userRoles = mock(SysUserRoleRepository.class);
        when(userRoles.rolesOfUser(anyString())).thenReturn(List.of());
        RolePermissionService rolePermissions = mock(RolePermissionService.class);
        when(rolePermissions.expand(anyCollection())).thenReturn(Set.of());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        when(ops.increment(anyString())).thenReturn(1L);

        AuthProperties props = new AuthProperties();
        AuditApi audit = mock(AuditApi.class);
        svc = new AuthService(users, passwords, tokens, members, workspaces,
                userRoles, rolePermissions, redis, props, audit);
    }

    @Test
    void loginSuccess() {
        AuthService.LoginResult r = svc.login("alice", "GoodPass!12", "127.0.0.1");
        assertEquals("tka_at", r.accessToken());
        assertEquals("alice", r.user().username());
    }

    @Test
    void loginFail() {
        assertThrows(BizException.class, () -> svc.login("alice", "bad", "127.0.0.1"));
    }

    @Test
    void legacySha256UpgradedOnLogin() {
        AuthUserDO legacy = new AuthUserDO();
        legacy.setId(1002L);
        legacy.setUsername("bob");
        legacy.setPasswordHash(PasswordService.sha256("legacy-pass"));
        legacy.setDisplayName("Bob");
        legacy.setEnabled(true);
        legacy.setMustChangePassword(false);
        users.insert(legacy);

        svc.login("bob", "legacy-pass", "127.0.0.1");
        AuthUserDO after = users.findByUsername("bob");
        assertTrue(PasswordService.isBcrypt(after.getPasswordHash()));
        assertTrue(after.getPasswordHash().startsWith("$2a$12$") || after.getPasswordHash().startsWith("$2b$12$"));
    }
}
