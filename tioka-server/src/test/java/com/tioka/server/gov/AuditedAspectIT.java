package com.tioka.server.gov;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GOV-02：{@code @Audited} 切面事务语义（经真实 AgentService）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class AuditedAspectIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditApi audit;

    @Test
    void successInSameTx() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String agentId = createAgent(admin);

        mockMvc.perform(admin.auth(post("/api/agents/{id}/disable", agentId)))
                .andExpect(status().isOk());

        List<AuditEvent> rows = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), admin.userId(), "agent.disable", "agent", agentId,
                AuditOutcome.SUCCESS, null, null, null, 50));
        assertFalse(rows.isEmpty(), "业务成功应有一条 SUCCESS 审计");
    }

    @Test
    void rollbackRemovesSuccessAudit() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String agentId = createAgent(admin);

        // revision 冲突 → BizException，切面写 ERROR（独立事务），无 SUCCESS
        mockMvc.perform(admin.auth(post("/api/agents/{id}/publish", agentId))
                        .contentType("application/json")
                        .content("{\"revision\":999999,\"note\":\"gov02\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        List<AuditEvent> success = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), admin.userId(), "agent.publish", "agent", agentId,
                AuditOutcome.SUCCESS, null, null, null, 50));
        assertTrue(success.isEmpty(), "失败路径不应留下 SUCCESS");

        List<AuditEvent> errors = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), admin.userId(), "agent.publish", "agent", agentId,
                AuditOutcome.ERROR, null, null, null, 50));
        assertFalse(errors.isEmpty(), "业务失败应有一条 ERROR 审计（REQUIRES_NEW）");
    }

    private String createAgent(TestFixtures.Actor admin) throws Exception {
        MvcResult created = mockMvc.perform(admin.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"gov02-aspect","workspaceId":"%s"}
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }
}
