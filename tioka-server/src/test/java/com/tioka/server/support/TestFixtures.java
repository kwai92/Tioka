package com.tioka.server.support;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.system.application.AuthService;
import com.tioka.system.application.UserAdminService;
import com.tioka.system.application.WorkspaceService;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysUserRoleRepository;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ENG-01 / FND-01 / FND-04：每用例独立空间 + 已认证令牌。
 */
@Component
public class TestFixtures {
    private static final AtomicLong SEQ = new AtomicLong(1);

    private final WorkspaceService workspaces;
    private final AuthService auth;
    private final SysMemberRepository members;
    private final SysUserRoleRepository userRoles;
    private final UserAdminService userAdmin;
    private final PrincipalHolder principals;

    public TestFixtures(WorkspaceService workspaces,
                        AuthService auth,
                        SysMemberRepository members,
                        SysUserRoleRepository userRoles,
                        UserAdminService userAdmin,
                        PrincipalHolder principals) {
        this.workspaces = workspaces;
        this.auth = auth;
        this.members = members;
        this.userRoles = userRoles;
        this.userAdmin = userAdmin;
        this.principals = principals;
    }

    public record Actor(String userId, String workspaceId, String token, String username, String password) {
        public Actor(String userId, String workspaceId, String token) {
            this(userId, workspaceId, token, null, null);
        }

        public MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
            MockHttpServletRequestBuilder out = b.header("X-Workspace-Id", workspaceId);
            if (token != null && !token.isBlank()) {
                out = out.header("Authorization", "Bearer " + token);
            }
            return out;
        }
    }

    /**
     * 以 bootstrap 管理员身份创建独立空间，并在该空间挂上指定角色；
     * 同时确保管理员持有系统级 {@code tenant_admin}（sys_user_role）。
     */
    public Actor newActor(String role) {
        AuthService.LoginResult login = auth.login(
                "admin", IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD, "127.0.0.1");
        long n = SEQ.getAndIncrement();
        String name = "it-" + role + "-" + n + "-" + UUID.randomUUID().toString().substring(0, 8);
        long ownerId = Long.parseLong(login.user().id());
        WorkspaceService.Workspace ws = workspaces.create(name, ownerId);
        members.upsert(ws.id(), login.user().id(), role);
        if (!"workspace_admin".equals(role) && !"tenant_admin".equals(role)) {
            members.upsert(ws.id(), login.user().id(), "workspace_admin");
        }
        userRoles.upsert(login.user().id(), "tenant_admin");
        return new Actor(login.user().id(), ws.id(), login.accessToken());
    }

    /**
     * 在已有空间中创建仅持指定角色、无 tenant_admin 的普通用户（FND-04 权限/ACL 验收）。
     */
    public Actor newLimitedUser(Actor admin, String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "lim-" + role + "-" + suffix;
        String password = "LimitedPass!23456";
        UserAdminService.CreatedUser created = principals.runAs(
                new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                        Set.of("tenant_admin"), Set.of("*"), "password"),
                () -> userAdmin.create(username, role + "-user", password));
        members.upsert(admin.workspaceId(), created.id(), role);
        AuthService.LoginResult login = auth.login(username, password, "127.0.0.1");
        return new Actor(created.id(), admin.workspaceId(), login.accessToken(), username, password);
    }

    public Actor newWorkspace() {
        return newActor("member");
    }
}
