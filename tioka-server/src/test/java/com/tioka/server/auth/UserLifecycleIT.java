package com.tioka.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.application.TurnSignalService;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FND-04e：用户禁用 / 成员移除生命周期。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class UserLifecycleIT {

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
    @Autowired RuntimeSessionRepository sessions;
    @Autowired TurnRepository turns;

    @Test
    void disableRevokesTokensAndCancelsTurns() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "dis-" + suffix;
        String password = "DisablePass!23456";

        UserAdminService.CreatedUser created = principals.runAs(
                new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                        Set.of("tenant_admin"), Set.of("*"), "password"),
                () -> userAdmin.create(username, "ToDisable", password));
        members.upsert(admin.workspaceId(), created.id(), "member");
        AuthService.LoginResult login = auth.login(username, password, "127.0.0.1");
        String token = login.accessToken();

        String turnId = seedQueuedTurn(created.id(), admin.workspaceId());

        mockMvc.perform(get("/api/system/workspaces/mine")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isOk());

        mockMvc.perform(admin.auth(put("/api/system/users/{id}/disable", created.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/system/workspaces/mine")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isUnauthorized());

        RuntimeTurnDO cancelled = awaitCancelled(turnId, 5_000);
        assertEquals(TurnStatus.CANCELLED.name(), cancelled.getStatus());
        assertEquals(TurnSignalService.REASON_PRINCIPAL_REVOKED, cancelled.getErrorCode());
    }

    @Test
    void memberRemovedLosesWorkspace() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "rm-" + suffix;
        String password = "RemovePass!23456";

        MvcResult created = mockMvc.perform(admin.auth(post("/api/system/users"))
                        .contentType("application/json")
                        .content("""
                                {"username":"%s","displayName":"Rm","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(admin.auth(put("/api/system/workspaces/{id}/members/{uid}",
                        admin.workspaceId(), userId))
                        .contentType("application/json")
                        .content("{\"role\":\"member\"}"))
                .andExpect(status().isOk());

        AuthService.LoginResult login = auth.login(username, password, "127.0.0.1");
        String turnId = seedQueuedTurn(userId, admin.workspaceId());

        mockMvc.perform(admin.auth(delete("/api/system/workspaces/{id}/members/{uid}",
                        admin.workspaceId(), userId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/system/workspaces/mine")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));

        RuntimeTurnDO cancelled = awaitCancelled(turnId, 5_000);
        assertEquals(TurnStatus.CANCELLED.name(), cancelled.getStatus());
        assertEquals(TurnSignalService.REASON_PRINCIPAL_REVOKED, cancelled.getErrorCode());
    }

    private String seedQueuedTurn(String userId, String workspaceId) {
        return principals.runAs(SystemPrincipals.system(), () -> {
            String sessionId = Ids.newId("thr");
            RuntimeSessionDO s = new RuntimeSessionDO();
            s.setId(sessionId);
            s.setStatus("OPEN");
            s.setAgentVersionId("agent_v1");
            s.setWorkspaceId(workspaceId);
            s.setOwnerId(userId);
            s.setLastItemSeq(0L);
            s.setOrigin("chat");
            s.setDepth(0);
            sessions.upsert(s);

            String turnId = Ids.newId("turn");
            RuntimeTurnDO t = new RuntimeTurnDO();
            t.setId(turnId);
            t.setSessionId(sessionId);
            t.setSeq(turns.nextSeq(sessionId));
            t.setStatus(TurnStatus.QUEUED.name());
            t.setOrigin("chat");
            t.setClientMsgId(Ids.newId("cm"));
            t.setRunAsJson("{\"userId\":\"" + userId + "\",\"workspaceId\":\"" + workspaceId
                    + "\",\"type\":\"USER\"}");
            t.setStepCount(0);
            t.setLeaseVersion(0L);
            turns.insert(t);
            return turnId;
        });
    }

    private RuntimeTurnDO awaitCancelled(String turnId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            RuntimeTurnDO row = principals.runAs(SystemPrincipals.system(),
                    () -> turns.findById(turnId).orElseThrow());
            if (TurnStatus.CANCELLED.name().equals(row.getStatus())) {
                return row;
            }
            Thread.sleep(40);
        }
        RuntimeTurnDO last = principals.runAs(SystemPrincipals.system(),
                () -> turns.findById(turnId).orElseThrow());
        fail("turn 未在 " + timeoutMs + "ms 内 CANCELLED: status=" + last.getStatus()
                + " errorCode=" + last.getErrorCode());
        return last;
    }
}
