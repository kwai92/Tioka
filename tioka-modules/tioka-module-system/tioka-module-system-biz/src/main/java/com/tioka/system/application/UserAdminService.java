package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.event.UserDisabledEvent;
import com.tioka.system.api.event.UserEnabledEvent;
import com.tioka.system.application.auth.PasswordService;
import com.tioka.system.application.lifecycle.AfterCommitEvents;
import com.tioka.system.application.lifecycle.IdentityOutboxBridge;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class UserAdminService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();

    private final AuthUserRepository users;
    private final PasswordService passwords;
    private final PrincipalHolder principals;
    private final ApplicationEventPublisher events;
    private final AuditApi audit;
    private final IdentityOutboxBridge identityOutbox;

    public UserAdminService(AuthUserRepository users,
                            PasswordService passwords,
                            PrincipalHolder principals,
                            ApplicationEventPublisher events,
                            AuditApi audit,
                            IdentityOutboxBridge identityOutbox) {
        this.users = users;
        this.passwords = passwords;
        this.principals = principals;
        this.events = events;
        this.audit = audit;
        this.identityOutbox = identityOutbox;
    }

    /** HCI-03：用户列表搜索。 */
    public Page<UserView> list(String q, int page, int size) {
        requireSystemAdmin();
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * s;
        List<AuthUserDO> rows = users.search(q, offset, s);
        List<UserView> items = new ArrayList<>(rows.size());
        for (AuthUserDO row : rows) {
            items.add(toView(row));
        }
        return new Page<>(items, users.countSearch(q), p, s);
    }

    @Transactional
    public CreatedUser create(String username, String displayName, String password) {
        requireSystemAdmin();
        if (username == null || username.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "username 必填");
        }
        if (users.findByUsername(username) != null) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        boolean generated = password == null || password.isBlank();
        String raw = generated ? randomPassword(16) : password;
        if (raw.length() < 10) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 10 位");
        }
        AuthUserDO row = new AuthUserDO();
        row.setId(users.nextId());
        row.setUsername(username.trim());
        row.setDisplayName(displayName == null || displayName.isBlank() ? username.trim() : displayName.trim());
        row.setPasswordHash(passwords.hash(raw));
        row.setEnabled(true);
        row.setIdp("local");
        row.setMustChangePassword(generated);
        row.setDefaultWorkspaceId(principals.current().workspaceId());
        users.insert(row);
        return new CreatedUser(String.valueOf(row.getId()), generated ? raw : null);
    }

    /** FND-04e：禁用用户 → afterCommit 发 {@link UserDisabledEvent}。 */
    @Transactional
    public void disable(String userId) {
        requireSystemAdmin();
        AuthUserDO row = requireUser(userId);
        if (!Boolean.TRUE.equals(row.getEnabled())) {
            return;
        }
        row.setEnabled(false);
        users.update(row);
        String operatorId = principals.current().userId();
        Instant at = Instant.now();
        audit.record(new AuditEvent(null, at, principals.current().workspaceId(),
                "user", operatorId, "user.disable", "user", userId,
                AuditOutcome.SUCCESS, null, null, null, null, null, null, Map.of()));
        identityOutbox.publishUserDisabled(userId, operatorId, at);
        AfterCommitEvents.publish(events, new UserDisabledEvent(userId, operatorId, at));
    }

    /** FND-04e：启用用户 → afterCommit 发 {@link UserEnabledEvent}。 */
    @Transactional
    public void enable(String userId) {
        requireSystemAdmin();
        AuthUserDO row = requireUser(userId);
        if (Boolean.TRUE.equals(row.getEnabled())) {
            return;
        }
        row.setEnabled(true);
        users.update(row);
        String operatorId = principals.current().userId();
        Instant at = Instant.now();
        audit.record(new AuditEvent(null, at, principals.current().workspaceId(),
                "user", operatorId, "user.enable", "user", userId,
                AuditOutcome.SUCCESS, null, null, null, null, null, null, Map.of()));
        AfterCommitEvents.publish(events, new UserEnabledEvent(userId, operatorId, at));
    }

    /** HCI-03：重置密码，明文仅返回一次。 */
    @Transactional
    public ResetPasswordResult resetPassword(String userId) {
        requireSystemAdmin();
        AuthUserDO row = requireUser(userId);
        String raw = randomPassword(16);
        row.setPasswordHash(passwords.hash(raw));
        row.setMustChangePassword(true);
        users.update(row);
        String operatorId = principals.current().userId();
        Instant at = Instant.now();
        audit.record(new AuditEvent(null, at, principals.current().workspaceId(),
                "user", operatorId, "user.reset_password", "user", userId,
                AuditOutcome.SUCCESS, null, null, null, null, null, null, Map.of()));
        return new ResetPasswordResult(raw);
    }

    private static UserView toView(AuthUserDO row) {
        return new UserView(
                String.valueOf(row.getId()),
                row.getUsername(),
                row.getDisplayName(),
                Boolean.TRUE.equals(row.getEnabled()),
                row.getIdp(),
                Boolean.TRUE.equals(row.getMustChangePassword()));
    }

    private AuthUserDO requireUser(String userId) {
        long id;
        try {
            id = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        AuthUserDO row = users.findById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return row;
    }

    private void requireSystemAdmin() {
        if (!principals.current().hasPermission(com.tioka.framework.security.Permissions.TENANT_ADMIN)
                && !principals.current().hasPermission("*")) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    private static String randomPassword(int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)];
        }
        return new String(buf);
    }

    public record CreatedUser(String id, String initialPassword) {}

    public record ResetPasswordResult(String initialPassword) {}

    public record UserView(String id, String username, String displayName, boolean enabled,
                           String idp, boolean mustChangePassword) {}

    public record Page<T>(List<T> items, long total, int page, int size) {}
}
