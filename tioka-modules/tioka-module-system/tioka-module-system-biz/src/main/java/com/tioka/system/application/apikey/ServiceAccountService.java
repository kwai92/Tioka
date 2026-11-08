package com.tioka.system.application.apikey;

import com.tioka.system.api.authz.SystemRoles;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.api.audit.Audited;
import com.tioka.system.application.WorkspaceService;
import com.tioka.system.application.auth.PasswordService;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysRoleRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.persistence.SysRoleDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * 服务账号创建（FND-06）：{@code auth_user.type=SERVICE}，无登录密码，按普通成员入空间。
 */
@Service
public class ServiceAccountService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private final AuthUserRepository users;
    private final PasswordService passwords;
    private final SysMemberRepository members;
    private final SysRoleRepository roles;
    private final WorkspaceService workspaces;
    private final PrincipalHolder principals;

    public ServiceAccountService(AuthUserRepository users,
                                 PasswordService passwords,
                                 SysMemberRepository members,
                                 SysRoleRepository roles,
                                 WorkspaceService workspaces,
                                 PrincipalHolder principals) {
        this.users = users;
        this.passwords = passwords;
        this.members = members;
        this.roles = roles;
        this.workspaces = workspaces;
        this.principals = principals;
    }

    @Transactional
    @Audited(action = "service_account.create", resourceType = "user",
            include = {"name", "workspaceId", "role"})
    public Created create(String name, String workspaceId, String role) {
        requireSystemAdmin();
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 必填");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "workspaceId 必填");
        }
        String roleCode = role == null || role.isBlank() ? "member" : role.trim();
        SysRoleDO r = roles.findByCode(roleCode);
        if (r == null || !"WORKSPACE".equalsIgnoreCase(r.getScope())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "role 必须是 WORKSPACE 范围角色");
        }
        workspaces.require(workspaceId);

        long id = users.nextId();
        AuthUserDO row = new AuthUserDO();
        row.setId(id);
        row.setUsername("svc_" + id);
        row.setDisplayName(name.trim());
        // password_hash NOT NULL：写入不可用哈希，登录路径另按 type 拒绝
        row.setPasswordHash(passwords.hash(randomSecret(32)));
        row.setEnabled(true);
        row.setIdp("service");
        row.setMustChangePassword(false);
        row.setDefaultWorkspaceId(workspaceId);
        row.setType("SERVICE");
        users.insert(row);
        members.upsert(workspaceId, String.valueOf(id), roleCode);
        return new Created(String.valueOf(id));
    }

    private void requireSystemAdmin() {
        var p = principals.current();
        if (!p.hasPermission(Permissions.TENANT_ADMIN) && !p.hasPermission("*")
                && !p.roles().contains(SystemRoles.SYSTEM_ADMIN)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    private static String randomSecret(int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }

    public record Created(String userId) {}
}
