package com.tioka.server.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import org.junit.jupiter.api.Disabled;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闸门 G1：FND-04、CON-01、GOV-02。用例说明见 docs/architecture/construction/M1-01、M1-04。
 *
 * <p>W6：第 1 条转绿；其余待 CON-01 / GOV-02。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("gate")
class G1PermissionGateIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditApi audit;

    /** 普通成员修改他人 Agent 返回 403，审计有 DENIED 记录。 */
    @Test
    void memberCannotEditOthersAgent() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "builder");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");

        MvcResult created = mockMvc.perform(owner.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"g1-owned","workspaceId":"%s"}
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isOk())
                .andReturn();
        String agentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(member.auth(put("/api/agents/{id}", agentId))
                        .contentType("application/json")
                        .content("{\"name\":\"g1-hacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        List<?> denied = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), member.userId(), null, null, null,
                AuditOutcome.DENIED, null, null, null, 50));
        assertFalse(denied.isEmpty());
    }

    /** 无 MCP 授权的工具调用被拒，模型收到拒绝结果，审计有 tool.invoke DENIED。 */
    @Test
    @Disabled("骨架：待 CON-01 实现后启用")
    void unauthorizedToolCallDeniedAndAudited() {
    }
}
