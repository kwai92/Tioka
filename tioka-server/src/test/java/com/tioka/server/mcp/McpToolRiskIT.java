package com.tioka.server.mcp;

import com.jayway.jsonpath.JsonPath;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FE-01：PATCH mcp_tool.risk、非法枚举、无权限 / 404，以及 refresh 保留已配置 risk。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class McpToolRiskIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;

    @Test
    void patchSuccessAndRefreshPreservesRisk() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String serverId = registerAndRefresh(admin);

        mockMvc.perform(admin.auth(patch("/api/mcp/servers/" + serverId + "/tools/mcp_echo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"risk\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("mcp_echo"))
                .andExpect(jsonPath("$.data.risk").value("READ"));

        mockMvc.perform(admin.auth(get("/api/mcp/servers/" + serverId + "/tools")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].risk").value("READ"));

        mockMvc.perform(admin.auth(post("/api/mcp/servers/" + serverId + "/refresh-tools"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("mcp_echo"))
                .andExpect(jsonPath("$.data[0].risk").value("READ"));
    }

    @Test
    void patchRejectsInvalidRisk() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String serverId = registerAndRefresh(admin);

        mockMvc.perform(admin.auth(patch("/api/mcp/servers/" + serverId + "/tools/mcp_echo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"risk\":\"BOOM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void patchForbiddenOrNotFound() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String serverId = registerAndRefresh(admin);
        TestFixtures.Actor viewer = fixtures.newLimitedUser(admin, "viewer");

        mockMvc.perform(viewer.auth(patch("/api/mcp/servers/" + serverId + "/tools/mcp_echo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"risk\":\"DESTRUCTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(admin.auth(patch("/api/mcp/servers/" + serverId + "/tools/no_such_tool"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"risk\":\"WRITE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        mockMvc.perform(admin.auth(patch("/api/mcp/servers/mcp_missing/tools/mcp_echo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"risk\":\"WRITE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    private String registerAndRefresh(TestFixtures.Actor admin) throws Exception {
        MvcResult created = mockMvc.perform(admin.auth(post("/api/mcp/servers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"fe01-mcp\",\"baseUrl\":\"mock://mcp\",\"bearerToken\":\"tok\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String serverId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        MvcResult refreshed = mockMvc.perform(admin.auth(post("/api/mcp/servers/" + serverId + "/refresh-tools"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("mcp_echo"))
                .andExpect(jsonPath("$.data[0].risk").value("WRITE"))
                .andReturn();
        assertEquals("WRITE",
                (String) JsonPath.read(refreshed.getResponse().getContentAsString(), "$.data[0].risk"));
        return serverId;
    }
}
