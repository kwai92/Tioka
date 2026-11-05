package com.tioka.server.support;

import com.tioka.system.application.AuthService;
import com.tioka.system.application.WorkspaceService;
import com.tioka.system.infrastructure.SysMemberRepository;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ENG-01 / FND-01：每用例独立空间 + 已认证令牌。
 */
@Component
public class TestFixtures {
    private static final AtomicLong SEQ = new AtomicLong(1);

    private final WorkspaceService workspaces;
    private final AuthService auth;
    private final SysMemberRepository members;

    public TestFixtures(WorkspaceService workspaces, AuthService auth, SysMemberRepository members) {
        this.workspaces = workspaces;
        this.auth = auth;
        this.members = members;
    }

    public record Actor(String userId, String workspaceId, String token) {
        public MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
            MockHttpServletRequestBuilder out = b.header("X-Workspace-Id", workspaceId);
            if (token != null && !token.isBlank()) {
                out = out.header("Authorization", "Bearer " + token);
            }
            return out;
        }
    }

    public Actor newActor(String role) {
        AuthService.LoginResult login = auth.login(
                "admin", IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD, "127.0.0.1");
        long n = SEQ.getAndIncrement();
        String name = "it-" + role + "-" + n + "-" + UUID.randomUUID().toString().substring(0, 8);
        long ownerId = Long.parseLong(login.user().id());
        WorkspaceService.Workspace ws = workspaces.create(name, ownerId);
        members.upsert(ws.id(), login.user().id(), role);
        if (!"tenant_admin".equals(role)) {
            members.upsert(ws.id(), login.user().id(), "tenant_admin");
        }
        return new Actor(login.user().id(), ws.id(), login.accessToken());
    }

    public Actor newWorkspace() {
        return newActor("member");
    }
}
