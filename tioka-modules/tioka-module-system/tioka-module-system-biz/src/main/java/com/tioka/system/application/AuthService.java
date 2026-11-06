package com.tioka.system.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
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
import java.util.Map;

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
    private final AuditApi audit;

    public AuthService(AuthUserRepository users,
                       PasswordService passwords,
                       TokenService tokens,
                       SysMemberRepository members,
                       SysWorkspaceRepository workspaces,
                       StringRedisTemplate redis,
                       AuthProperties props,
                       AuditApi audit) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.members = members;
        this.workspaces = workspaces;
        this.redis = redis;
        this.props = props;
        this.audit = audit;
    }

    public LoginResult login(String username, String password, String clientIp) {
        assertNotRateLimited(clientIp, username);
        AuthUserDO row = users.findByUsername(username);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
            recordFailure(clientIp, username);
            if (row != null && !Boolean.TRUE.equals(row.getEnabled())) {
                auditAuth("auth.login", null, username, clientIp, AuditOutcome.DENIED, "账号已禁用");
                throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
            }
            auditAuth("auth.login", null, username, clientIp, AuditOutcome.DENIED, "用户名或密码错误");
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!passwords.matches(password, row.getPasswordHash())) {
            recordFailure(clientIp, username);
            auditAuth("auth.login", String.valueOf(row.getId()), username, clientIp,
                    AuditOutcome.DENIED, "用户名或密码错误");
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        clearFailures(clientIp, username);
        if (passwords.needsUpgrade(row.getPasswordHash())) {
            row.setPasswordHash(passwords.hash(password));
            users.update(row);
        }
        LoginResult result = issueLogin(row);
        auditAuth("auth.login", result.user().id(), username, clientIp, AuditOutcome.SUCCESS, null);
        return result;
    }

    public LoginResult refresh(String refreshToken) {
        try {
            TokenService.TokenPair pair = tokens.refresh(refreshToken);
            long userId = tokens.verifyAccess(pair.accessToken())
                    .map(TokenService.TokenClaims::userId)
                    .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "刷新失败"));
            AuthUserDO row = users.findById(userId);
            if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
                tokens.revokeAll(userId);
                throw new BizException(ErrorCode.UNAUTHORIZED, "用户无效");
            }
            LoginResult result = toLoginResult(row, pair);
            auditAuth("auth.refresh", result.user().id(), row.getUsername(), null,
                    AuditOutcome.SUCCESS, null);
            return result;
        } catch (BizException ex) {
            auditAuth("auth.refresh", null, null, null,
                    ex.isDenied() ? AuditOutcome.DENIED : AuditOutcome.ERROR, ex.getMessage());
            throw ex;
        }
    }

    public void logout(String accessToken) {
        Long uid = resolveUserId(accessToken);
        tokens.revoke(accessToken);
        auditAuth("auth.logout", uid == null ? null : String.valueOf(uid), null, null,
                AuditOutcome.SUCCESS, null);
    }

    private void auditAuth(String action, String actorId, String username, String clientIp,
                           AuditOutcome outcome, String reason) {
        try {
            audit.recordIndependent(AuditEvent.builder()
                    .ts(Instant.now())
                    .actorType("user")
                    .actorId(actorId == null ? (username == null ? "unknown" : username) : actorId)
                    .action(action)
                    .resourceType("auth")
                    .outcome(outcome)
                    .reason(reason)
                    .clientIp(clientIp)
                    .details(username == null ? Map.of() : Map.of("username", username))
                    .build());
        } catch (RuntimeException ignored) {
            // 审计失败不影响认证主路径
        }
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
