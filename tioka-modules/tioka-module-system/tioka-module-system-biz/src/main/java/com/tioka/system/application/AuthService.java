package com.tioka.system.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.IdPAdapter;
import com.tioka.system.application.auth.PasswordService;
import com.tioka.system.application.auth.TokenService;
import com.tioka.system.config.AuthProperties;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysWorkspaceRepository;
import com.tioka.system.infrastructure.dataobject.SysMemberDO;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.persistence.SysWorkspaceDO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(5);

    private final AuthUserRepository users;
    private final PasswordService passwords;
    private final TokenService tokens;
    private final SysMemberRepository members;
    private final SysWorkspaceRepository workspaces;
    private final StringRedisTemplate redis;
    private final AuthProperties props;

    public AuthService(AuthUserRepository users,
                       PasswordService passwords,
                       TokenService tokens,
                       SysMemberRepository members,
                       SysWorkspaceRepository workspaces,
                       StringRedisTemplate redis,
                       AuthProperties props) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.members = members;
        this.workspaces = workspaces;
        this.redis = redis;
        this.props = props;
    }

    public LoginResult login(String username, String password, String clientIp) {
        assertNotRateLimited(clientIp, username);
        AuthUserDO row = users.findByUsername(username);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
            recordFailure(clientIp, username);
            if (row != null && !Boolean.TRUE.equals(row.getEnabled())) {
                throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
            }
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!passwords.matches(password, row.getPasswordHash())) {
            recordFailure(clientIp, username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        clearFailures(clientIp, username);
        if (passwords.needsUpgrade(row.getPasswordHash())) {
            row.setPasswordHash(passwords.hash(password));
            users.update(row);
        }
        return issueLogin(row);
    }

    public LoginResult loginFederated(IdPAdapter.FederatedIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "联邦身份无效");
        }
        String username = identity.email() == null || identity.email().isBlank()
                ? "oidc:" + identity.subject()
                : identity.email();
        AuthUserDO row = users.findByUsername(username);
        if (row == null) {
            AuthUserDO created = new AuthUserDO();
            created.setId(users.nextId());
            created.setUsername(username);
            created.setPasswordHash(passwords.hash(UUID.randomUUID().toString()));
            created.setDisplayName(identity.displayName() == null ? username : identity.displayName());
            created.setEnabled(true);
            created.setIdp("oidc");
            created.setExternalSubject(identity.subject());
            created.setMustChangePassword(false);
            created.setDefaultWorkspaceId("ws_default");
            users.insert(created);
            row = users.findByUsername(username);
        }
        if (!Boolean.TRUE.equals(row.getEnabled())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        return issueLogin(row);
    }

    public LoginResult refresh(String refreshToken) {
        TokenService.TokenPair pair = tokens.refresh(refreshToken);
        long userId = tokens.verifyAccess(pair.accessToken())
                .map(TokenService.TokenClaims::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "刷新失败"));
        AuthUserDO row = users.findById(userId);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
            tokens.revokeAll(userId);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户无效");
        }
        return toLoginResult(row, pair);
    }

    public void logout(String accessToken) {
        tokens.revoke(accessToken);
    }

    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 10) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码至少 10 位");
        }
        AuthUserDO row = users.findById(userId);
        if (row == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        if (!passwords.matches(oldPassword, row.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "旧密码错误");
        }
        row.setPasswordHash(passwords.hash(newPassword));
        row.setMustChangePassword(false);
        users.update(row);
        tokens.revokeAll(userId);
    }

    public Long resolveUserId(String accessToken) {
        return tokens.verifyAccess(accessToken).map(TokenService.TokenClaims::userId).orElse(null);
    }

    public MeResult me(long userId) {
        AuthUserDO row = users.findById(userId);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        List<WorkspaceView> ws = workspacesOf(row);
        return new MeResult(
                userView(row),
                ws,
                row.getDefaultWorkspaceId(),
                List.of()
        );
    }

    private LoginResult issueLogin(AuthUserDO row) {
        TokenService.TokenPair pair = tokens.issue(row.getId());
        return toLoginResult(row, pair);
    }

    private LoginResult toLoginResult(AuthUserDO row, TokenService.TokenPair pair) {
        return new LoginResult(
                pair.accessToken(),
                pair.refreshToken(),
                pair.accessExpiresAt(),
                userView(row),
                workspacesOf(row)
        );
    }

    private UserView userView(AuthUserDO row) {
        return new UserView(
                String.valueOf(row.getId()),
                row.getUsername(),
                row.getDisplayName(),
                Boolean.TRUE.equals(row.getMustChangePassword())
        );
    }

    private List<WorkspaceView> workspacesOf(AuthUserDO row) {
        List<SysMemberDO> memberships = members.findByUserId(String.valueOf(row.getId()));
        List<WorkspaceView> out = new ArrayList<>();
        for (SysMemberDO m : memberships) {
            SysWorkspaceDO ws = workspaces.findById(m.getWorkspaceId());
            if (ws == null) {
                continue;
            }
            out.add(new WorkspaceView(ws.getId(), ws.getName(), m.getRoleCode()));
        }
        return out;
    }

    private void assertNotRateLimited(String clientIp, String username) {
        String key = failKey(clientIp, username);
        String raw = redis.opsForValue().get(key);
        if (raw != null) {
            try {
                if (Integer.parseInt(raw) >= props.getLoginMaxFailures()) {
                    throw new BizException(ErrorCode.RATE_LIMITED, "登录失败次数过多，请稍后再试");
                }
            } catch (NumberFormatException ignored) {
                // reset bad value
            }
        }
    }

    private void recordFailure(String clientIp, String username) {
        String key = failKey(clientIp, username);
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redis.expire(key, FAIL_WINDOW);
        }
    }

    private void clearFailures(String clientIp, String username) {
        redis.delete(failKey(clientIp, username));
    }

    private static String failKey(String clientIp, String username) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        String u = username == null ? "" : username;
        return "auth:fail:" + ip + ":" + u;
    }

    public record LoginResult(
            String accessToken,
            @JsonIgnore String refreshToken,
            Instant expiresAt,
            UserView user,
            List<WorkspaceView> workspaces
    ) {}

    public record UserView(String id, String username, String displayName, boolean mustChangePassword) {}
    public record WorkspaceView(String id, String name, String role) {}
    public record MeResult(UserView user, List<WorkspaceView> workspaces, String currentWorkspaceId, List<String> permissions) {}
}
