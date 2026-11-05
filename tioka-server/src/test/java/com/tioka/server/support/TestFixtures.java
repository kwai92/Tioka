package com.tioka.server.support;

import com.tioka.system.application.WorkspaceService;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ENG-01：每用例独立空间（及后续独立用户/令牌）。
 * FND-01/FND-02 完成前：只建空间，令牌为空；请求仍带 {@code X-Workspace-Id}。
 */
@Component
public class TestFixtures {
    private static final AtomicLong SEQ = new AtomicLong(1);

    private final WorkspaceService workspaces;

    public TestFixtures(WorkspaceService workspaces) {
        this.workspaces = workspaces;
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

    /**
     * @param role member / builder / workspace_admin（FND-02 后写入 sys_member；M0 前期仅建空间）
     */
    public Actor newActor(String role) {
        long n = SEQ.getAndIncrement();
        String name = "it-" + role + "-" + n + "-" + UUID.randomUUID().toString().substring(0, 8);
        WorkspaceService.Workspace ws = workspaces.create(name, 1L);
        // FND-01 前无鉴权令牌；userId 暂用种子管理员
        return new Actor("1", ws.id(), "");
    }

    public Actor newWorkspace() {
        return newActor("member");
    }
}
