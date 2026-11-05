package com.tioka.system.application.auth;

import com.tioka.system.config.BootstrapProperties;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysWorkspaceRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.persistence.SysWorkspaceDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

@Component
@Order(100)
public class BootstrapAdminRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String WS_DEFAULT = "ws_default";
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();

    private final AuthUserRepository users;
    private final SysMemberRepository members;
    private final SysWorkspaceRepository workspaces;
    private final PasswordService passwords;
    private final BootstrapProperties bootstrap;
    private final Path homeDir;
    private final String configuredPassword;

    public BootstrapAdminRunner(AuthUserRepository users,
                                SysMemberRepository members,
                                SysWorkspaceRepository workspaces,
                                PasswordService passwords,
                                BootstrapProperties bootstrap,
                                @Value("${tioka.home:${user.home}/.tioka}") String home,
                                @Value("${tioka.bootstrap.admin-password:}") String configuredPassword) {
        this.users = users;
        this.members = members;
        this.workspaces = workspaces;
        this.passwords = passwords;
        this.bootstrap = bootstrap;
        this.homeDir = Path.of(home);
        this.configuredPassword = configuredPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        members.advisoryLockBootstrap();
        if (members.existsEnabledTenantAdmin()) {
            return;
        }
        String username = bootstrap.getAdminUsername() == null ? "admin" : bootstrap.getAdminUsername();
        AuthUserDO conflict = users.findByUsername(username);
        if (conflict != null) {
            conflict.setUsername(username + "_legacy_" + conflict.getId());
            users.update(conflict);
        }

        String envPassword = System.getenv("TIOKA_BOOTSTRAP_ADMIN_PASSWORD");
        String rawPassword = firstNonBlank(envPassword, configuredPassword);
        boolean generated = rawPassword == null;
        if (generated) {
            rawPassword = randomPassword(20);
        }

        AuthUserDO admin = new AuthUserDO();
        admin.setId(users.nextId());
        admin.setUsername(username);
        admin.setPasswordHash(passwords.hash(rawPassword));
        admin.setDisplayName("系统管理员");
        admin.setEnabled(true);
        admin.setIdp("local");
        admin.setDefaultWorkspaceId(WS_DEFAULT);
        admin.setMustChangePassword(true);
        users.insert(admin);

        ensureDefaultWorkspace(admin.getId());
        String uid = String.valueOf(admin.getId());
        members.upsert(WS_DEFAULT, uid, "workspace_admin");
        members.upsert(WS_DEFAULT, uid, "tenant_admin");

        Path out = homeDir.resolve("bootstrap-admin.txt");
        Files.createDirectories(homeDir);
        Files.writeString(out, "username=" + username + "\npassword=" + rawPassword + "\n");
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(out, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等非 POSIX 文件系统
        }
        log.warn("已创建初始管理员 username={} 密码已写入 {}（仅此一次）", username, out.toAbsolutePath());
        if (generated) {
            log.warn("初始管理员密码（请立即改密）: {}", rawPassword);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private void ensureDefaultWorkspace(long ownerUserId) {
        SysWorkspaceDO ws = workspaces.findById(WS_DEFAULT);
        if (ws == null) {
            SysWorkspaceDO created = new SysWorkspaceDO();
            created.setId(WS_DEFAULT);
            created.setName("默认空间");
            created.setOwnerUserId(ownerUserId);
            workspaces.insert(created);
        }
    }

    private static String randomPassword(int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)];
        }
        return new String(buf);
    }
}
