package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.IdPAdapter;
import com.tioka.system.domain.UserAccount;
import com.tioka.system.infrastructure.AuthTokenRepository;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private final AuthUserRepository users;
    private final AuthTokenRepository tokens;

    public AuthService(AuthUserRepository users, AuthTokenRepository tokens) {
        this.users = users;
        this.tokens = tokens;
        if (users.findById(1L) == null) {
            AuthUserDO admin = new AuthUserDO();
            admin.setId(1L);
            admin.setUsername("admin");
            admin.setPasswordHash(sha256("admin123"));
            admin.setDisplayName("管理员");
            admin.setEnabled(true);
            users.insert(admin);
        }
    }

    public LoginResult login(String username, String password) {
        UserAccount user = toAccount(users.findByUsername(username));
        if (user == null || !user.isEnabled() || !user.getPasswordHash().equals(sha256(password))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return issue(user);
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
            created.setPasswordHash(sha256(UUID.randomUUID().toString()));
            created.setDisplayName(identity.displayName() == null ? username : identity.displayName());
            created.setEnabled(true);
            users.insert(created);
            row = users.findByUsername(username);
        }
        return issue(toAccount(row));
    }

    public Long resolveUserId(String token) {
        return tokens.findUserId(token);
    }

    private LoginResult issue(UserAccount user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.save(token, user.getId());
        return new LoginResult(token, user.getId(), user.getUsername(), user.getDisplayName());
    }

    private static UserAccount toAccount(AuthUserDO row) {
        if (row == null) return null;
        UserAccount u = new UserAccount();
        u.setId(row.getId());
        u.setUsername(row.getUsername());
        u.setPasswordHash(row.getPasswordHash());
        u.setDisplayName(row.getDisplayName());
        u.setEnabled(Boolean.TRUE.equals(row.getEnabled()));
        return u;
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record LoginResult(String token, Long userId, String username, String displayName) {}
}
