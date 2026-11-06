package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.application.auth.PasswordService;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
public class UserAdminService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();

    private final AuthUserRepository users;
    private final PasswordService passwords;
    private final PrincipalHolder principals;

    public UserAdminService(AuthUserRepository users,
                            PasswordService passwords,
                            PrincipalHolder principals) {
        this.users = users;
        this.passwords = passwords;
        this.principals = principals;
    }

    @Transactional
    public CreatedUser create(String username, String displayName, String password) {
        requireTenantAdmin();
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

    private void requireTenantAdmin() {
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
}
