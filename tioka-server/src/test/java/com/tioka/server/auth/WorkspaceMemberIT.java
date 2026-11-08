package com.tioka.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.application.AuthService;
import com.tioka.system.application.UserAdminService;
import com.tioka.system.infrastructure.SysMemberRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class WorkspaceMemberIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService auth;
    @Autowired UserAdminService userAdmin;
    @Autowired SysMemberRepository members;
    @Autowired PrincipalHolder principals;

    @Test
    void addChangeRemoveMember() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "mem-" + suffix;
        String password = "MemberPass!23456";

        MvcResult created = mockMvc.perform(admin.auth(post("/api/system/users"))
                        .contentType("application/json")
                        .content("""
                                {"username":"%s","displayName":"Mem","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(admin.auth(put("/api/system/workspaces/{id}/members/{uid}", admin.workspaceId(), userId))
                        .contentType("application/json")
                        .content("{\"role\":\"builder\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(admin.auth(get("/api/system/workspaces/{id}/members", admin.workspaceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].userId", hasItem(userId)))
                .andExpect(jsonPath("$.data.items[?(@.userId=='" + userId + "')].role", hasItem("builder")));

        mockMvc.perform(admin.auth(put("/api/system/workspaces/{id}/members/{uid}", admin.workspaceId(), userId))
                        .contentType("application/json")
                        .content("{\"role\":\"member\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(admin.auth(delete("/api/system/workspaces/{id}/members/{uid}", admin.workspaceId(), userId)))
                .andExpect(status().isOk());
    }

    @Test
    void removeLastAdminConflict() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        mockMvc.perform(admin.auth(delete("/api/system/workspaces/{id}/members/{uid}",
                        admin.workspaceId(), admin.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902));

        mockMvc.perform(admin.auth(put("/api/system/workspaces/{id}/members/{uid}",
                        admin.workspaceId(), admin.userId()))
                        .contentType("application/json")
                        .content("{\"role\":\"member\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902));
    }

    @Test
    void removedMemberGetsWorkspaceForbidden() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "gone-" + suffix;
        String password = "GonePass!23456";

        MvcResult created = mockMvc.perform(admin.auth(post("/api/system/users"))
                        .contentType("application/json")
                        .content("""
                                {"username":"%s","displayName":"Gone","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(admin.auth(put("/api/system/workspaces/{id}/members/{uid}", admin.workspaceId(), userId))
                        .contentType("application/json")
                        .content("{\"role\":\"member\"}"))
                .andExpect(status().isOk());

        AuthService.LoginResult login = auth.login(username, password, "127.0.0.1");

        mockMvc.perform(get("/api/system/workspaces/mine")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isOk());

        mockMvc.perform(admin.auth(delete("/api/system/workspaces/{id}/members/{uid}", admin.workspaceId(), userId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/system/workspaces/mine")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    void nonAdminForbidden() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "plain-" + suffix;
        String password = "PlainPass!23456";

        UserAdminService.CreatedUser created = principals.runAs(
                new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                        Set.of("system_admin", "workspace_admin"), Set.of("*"), "password"),
                () -> userAdmin.create(username, "Plain", password));
        members.upsert(admin.workspaceId(), created.id(), "member");

        AuthService.LoginResult login = auth.login(username, password, "127.0.0.1");

        mockMvc.perform(get("/api/system/workspaces/{id}/members", admin.workspaceId())
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(put("/api/system/workspaces/{id}/members/{uid}", admin.workspaceId(), admin.userId())
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", admin.workspaceId())
                        .contentType("application/json")
                        .content("{\"role\":\"viewer\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossWorkspaceAdminCannotManageOrReadOtherWorkspace() throws Exception {
        // 两个独立空间；用户仅是 A 的 workspace_admin（无 system_admin）
        TestFixtures.Actor seed = fixtures.newActor("system_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "wsadmin-" + suffix;
        String password = "WsAdminPass!23456";

        UserAdminService.CreatedUser created = principals.runAs(
                new Principal(seed.userId(), seed.workspaceId(), PrincipalType.USER,
                        Set.of("system_admin"), Set.of("*"), "password"),
                () -> userAdmin.create(username, "WsAdmin", password));

        TestFixtures.Actor other = fixtures.newActor("workspace_admin");
        String wsA = seed.workspaceId();
        String wsB = other.workspaceId();
        members.upsert(wsA, created.id(), "workspace_admin");

        AuthService.LoginResult login = auth.login(username, password, "127.0.0.1");

        mockMvc.perform(put("/api/system/workspaces/{id}/members/{uid}", wsB, created.id())
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", wsA)
                        .contentType("application/json")
                        .content("{\"role\":\"workspace_admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(get("/api/system/workspaces/{id}/members", wsB)
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", wsA))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/system/workspaces/{id}", wsB)
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", wsA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }
}
