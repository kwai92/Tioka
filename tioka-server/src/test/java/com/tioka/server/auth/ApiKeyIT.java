package com.tioka.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FND-06：服务账号与 API Key。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class ApiKeyIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;

    @Test
    void keyBoundToPermissions() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String saId = createServiceAccount(admin, "member");
        String key = createApiKey(admin, saId, "bound", null, null);

        // member 有 chat:use → /me 可读
        mockMvc.perform(get("/api/system/auth/me")
                        .header("Authorization", "Bearer " + key)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.permissions").isArray());

        // member 无 tenant:admin → 用户列表 403
        mockMvc.perform(get("/api/system/users")
                        .header("Authorization", "Bearer " + key)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedKeyRejectedImmediately() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String saId = createServiceAccount(admin, "member");
        MvcResult created = mockMvc.perform(admin.auth(post("/api/system/api-keys"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceUserId":"%s","name":"rev","workspaceId":"%s"}
                                """.formatted(saId, admin.workspaceId())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        String id = data.path("id").asText();
        String key = data.path("key").asText();
        assertTrue(key.startsWith("tk_live_"));

        mockMvc.perform(get("/api/system/auth/me")
                        .header("Authorization", "Bearer " + key)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isOk());

        mockMvc.perform(admin.auth(delete("/api/system/api-keys/{id}", id)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/system/auth/me")
                        .header("Authorization", "Bearer " + key)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scopesNarrowPermissions() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        // builder 角色有 flow:run / agent:write 等；scopes 仅 chat:use
        String saId = createServiceAccount(admin, "builder");
        String key = createApiKey(admin, saId, "scoped",
                admin.workspaceId(),
                "[\"chat:use\"]");

        mockMvc.perform(get("/api/system/auth/me")
                        .header("Authorization", "Bearer " + key)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // agent:write 被 scopes 收窄 → 403
        mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + key)
                        .header("X-Workspace-Id", admin.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"nope-%s"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredKey401() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String saId = createServiceAccount(admin, "member");
        String past = Instant.now().minusSeconds(60).toString();
        MvcResult created = mockMvc.perform(admin.auth(post("/api/system/api-keys"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceUserId":"%s","name":"exp","workspaceId":"%s","expiresAt":"%s"}
                                """.formatted(saId, admin.workspaceId(), past)))
                .andExpect(status().isOk())
                .andReturn();
        String key = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("key").asText();

        mockMvc.perform(get("/api/system/auth/me")
                        .header("Authorization", "Bearer " + key)
                        .header("X-Workspace-Id", admin.workspaceId()))
                .andExpect(status().isUnauthorized());
    }

    private String createServiceAccount(TestFixtures.Actor admin, String role) throws Exception {
        MvcResult r = mockMvc.perform(admin.auth(post("/api/system/service-accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"sa-%s","workspaceId":"%s","role":"%s"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8),
                                admin.workspaceId(), role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").isString())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("userId").asText();
    }

    private String createApiKey(TestFixtures.Actor admin, String saId, String name,
                                String workspaceId, String scopesJson) throws Exception {
        StringBuilder body = new StringBuilder("{");
        body.append("\"serviceUserId\":\"").append(saId).append("\",");
        body.append("\"name\":\"").append(name).append("\"");
        if (workspaceId != null) {
            body.append(",\"workspaceId\":\"").append(workspaceId).append("\"");
        }
        if (scopesJson != null) {
            body.append(",\"scopes\":").append(scopesJson);
        }
        body.append("}");
        MvcResult r = mockMvc.perform(admin.auth(post("/api/system/api-keys"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value(org.hamcrest.Matchers.startsWith("tk_live_")))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("key").asText();
    }
}
