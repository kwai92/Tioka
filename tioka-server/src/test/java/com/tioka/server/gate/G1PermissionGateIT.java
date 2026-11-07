package com.tioka.server.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import com.tioka.model.api.dto.ProviderDtos.CreateBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.auth.PrincipalLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闸门 G1：FND-04、CON-01、GOV-02。用例说明见 docs/architecture/construction/M1-01、M1-04。
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
    @Autowired ToolGateway toolGateway;
    @Autowired PrincipalHolder principals;
    @Autowired PrincipalLoader principalLoader;
    @Autowired McpAdminService mcpAdmin;
    @Autowired AgentService agents;
    @Autowired AgentSnapshotApi snapshots;
    @Autowired ModelAdminService models;
    @Autowired JdbcTemplate jdbc;

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
    void unauthorizedToolCallDeniedAndAudited() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");

        String versionId = principals.runAs(adminPrincipal(admin), () -> {
            String tag = Ids.newId("g1p");
            var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
            String binding = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt"));
            String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
            models.publish(planId);

            McpStore.McpServer server = mcpAdmin.register(tag + "-mcp", "mock://local", null);
            mcpAdmin.refreshTools(server.id());
            jdbc.update("UPDATE mcp_server SET visibility = 'PRIVATE' WHERE id = ?", server.id());

            AgentStore.Agent agent = agents.create(tag + "-agent", admin.workspaceId());
            AgentSpec spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(
                            new AgentSpec.Instructions.Section("main", "call tools"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    new AgentSpec.ToolSet(List.of("echo"),
                            List.of(new AgentSpec.ToolSet.McpBinding(server.id(), List.of("mcp_echo"))),
                            List.of(), List.of()),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                    null, null, null, null);
            return agents.publishSpec(agent.id(), spec).versionId();
        });

        Principal memberP = principalLoader.load(Long.parseLong(member.userId()), member.workspaceId());
        Map<String, Object> toolCfg = principals.runAs(adminPrincipal(admin), () -> mcpGrantsOf(versionId));
        ToolProvider.ToolResult result = principals.runAs(memberP, () ->
                toolGateway.call(
                        ToolCallContexts.forTurn(memberP, "thr-g1", "turn-g1", versionId,
                                "chat", List.of(), toolCfg),
                        "mcp_echo", Map.of("text", "probe")));

        assertFalse(result.ok());
        assertTrue(result.content().contains("无权调用工具"),
                "模型应收到拒绝结果: " + result.content());

        List<AuditEvent> denied = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), member.userId(), "tool.invoke", "tool", "mcp_echo",
                AuditOutcome.DENIED, null, null, null, 20));
        assertFalse(denied.isEmpty(), "审计应有 tool.invoke DENIED");
    }

    private Map<String, Object> mcpGrantsOf(String versionId) {
        AgentSpec.ToolSet tools = snapshots.get(versionId).spec().tools();
        if (tools == null || tools.mcp() == null) {
            return Map.of("mcpGrants", List.of());
        }
        List<Map<String, Object>> grants = tools.mcp().stream()
                .map(b -> Map.<String, Object>of(
                        "serverId", b.serverId(),
                        "allowlist", b.allowlist() == null ? List.of() : b.allowlist()))
                .toList();
        return Map.of("mcpGrants", grants);
    }

    private static Principal adminPrincipal(TestFixtures.Actor admin) {
        return new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("tenant_admin", "workspace_admin"), Set.of("*"), "password");
    }
}
