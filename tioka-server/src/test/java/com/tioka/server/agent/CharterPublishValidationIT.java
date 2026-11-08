package com.tioka.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.authz.ResourceAclApi;
import com.tioka.system.infrastructure.SysResourceAclRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRU-01 / P3-A09：授权书发布校验。
 * <ul>
 *   <li>未归类写工具 → 发布失败</li>
 *   <li>SERVICE 模式无 USE → 发布失败</li>
 *   <li>ceiling L3 由非管理员发布 → 失败</li>
 * </ul>
 */
@SpringBootTest
@Tag("it")
class CharterPublishValidationIT {

    private static final String SA_RESOURCE = "service_account";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.trust.charter.enabled", () -> "true");
    }

    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired McpAdminService mcpAdmin;
    @Autowired PrincipalHolder principals;
    @Autowired TestFixtures fixtures;
    @Autowired ResourceAclApi resourceAcl;
    @Autowired SysResourceAclRepository acls;
    @Autowired ObjectMapper mapper;

    @Test
    void unclassifiedWriteToolRejected() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        principals.runAs(adminPrincipal(admin), () -> {
            String planId = publishPlan("unc");
            McpStore.McpServer server = mcpAdmin.register("unc-mcp", "mock://x", "t");
            mcpAdmin.refreshTools(server.id());
            resourceAcl.ensureOwner("mcp", server.id(), admin.userId());

            var agent = agents.create("unc-charter", admin.workspaceId());
            ObjectNode charter = baseCharter("DELEGATED", null);
            // 故意不把 mcp_echo（默认 WRITE）归入任何动作类别
            AgentSpec spec = spec(agent.name(), planId,
                    List.of(new AgentSpec.ToolSet.McpBinding(server.id(), List.of("mcp_echo"))),
                    charter);
            agents.saveDraft(agent.id(), spec, agents.getDraft(agent.id()).revision());
            AgentService.ValidateResult result = agents.validate(agent.id());
            assertFalse(result.ok(), () -> result.errors().toString());
            assertTrue(result.errors().stream().anyMatch(e -> "CHARTER_TOOL_UNCLASSIFIED".equals(e.code())),
                    () -> result.errors().toString());
            return null;
        });
    }

    @Test
    void serviceModeWithoutUseRejected() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor builder = fixtures.newLimitedUser(admin, "builder");
        String saId = "sa_no_use_" + admin.userId();

        principals.runAs(adminPrincipal(admin), () -> {
            String planId = publishPlan("svc");
            resourceAcl.ensureOwner(SA_RESOURCE, saId, admin.userId());

            principals.runAs(builderPrincipal(builder), () -> {
                var agent = agents.create("svc-charter", admin.workspaceId());
                ObjectNode charter = baseCharter("SERVICE", saId);
                addActionClass(charter, "echo", "Echo", List.of("mcp_echo"), "L2");
                AgentSpec spec = spec(agent.name(), planId, List.of(), charter);
                agents.saveDraft(agent.id(), spec, agents.getDraft(agent.id()).revision());
                AgentService.ValidateResult result = agents.validate(agent.id());
                assertFalse(result.ok(), () -> result.errors().toString());
                assertTrue(result.errors().stream()
                                .anyMatch(e -> "CHARTER_SERVICE_ACCOUNT_FORBIDDEN".equals(e.code())),
                        () -> result.errors().toString());
                return null;
            });
            return null;
        });
    }

    @Test
    void ceilingL3ByNonAdminRejected() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor builder = fixtures.newLimitedUser(admin, "builder");

        principals.runAs(adminPrincipal(admin), () -> {
            String planId = publishPlan("l3");
            principals.runAs(builderPrincipal(builder), () -> {
                var agent = agents.create("l3-charter", admin.workspaceId());
                ObjectNode charter = baseCharter("DELEGATED", null);
                addActionClass(charter, "echo", "Echo", List.of("mcp_echo"), "L3");
                AgentSpec spec = spec(agent.name(), planId, List.of(), charter);
                agents.saveDraft(agent.id(), spec, agents.getDraft(agent.id()).revision());
                AgentService.ValidateResult result = agents.validate(agent.id());
                assertFalse(result.ok(), () -> result.errors().toString());
                assertTrue(result.errors().stream()
                                .anyMatch(e -> "CHARTER_CEILING_REQUIRES_ADMIN".equals(e.code())),
                        () -> result.errors().toString());
                return null;
            });
            return null;
        });
    }

    @Test
    void serviceModeWithUseAndClassifiedPasses() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor builder = fixtures.newLimitedUser(admin, "builder");
        String saId = "sa_ok_" + admin.userId();

        principals.runAs(adminPrincipal(admin), () -> {
            String planId = publishPlan("ok");
            McpStore.McpServer server = mcpAdmin.register("ok-mcp", "mock://x", "t");
            mcpAdmin.refreshTools(server.id());
            resourceAcl.ensureOwner("mcp", server.id(), admin.userId());
            acls.upsert("mcp", server.id(), "user", builder.userId(), AccessLevel.USE.name());
            resourceAcl.ensureOwner(SA_RESOURCE, saId, admin.userId());
            acls.upsert(SA_RESOURCE, saId, "user", builder.userId(), AccessLevel.USE.name());

            principals.runAs(builderPrincipal(builder), () -> {
                var agent = agents.create("ok-charter", admin.workspaceId());
                ObjectNode charter = baseCharter("SERVICE", saId);
                addActionClass(charter, "echo", "Echo", List.of("mcp_echo"), "L2");
                AgentSpec spec = spec(agent.name(), planId,
                        List.of(new AgentSpec.ToolSet.McpBinding(server.id(), List.of("mcp_echo"))),
                        charter);
                agents.saveDraft(agent.id(), spec, agents.getDraft(agent.id()).revision());
                AgentService.ValidateResult result = agents.validate(agent.id());
                assertTrue(result.ok(), () -> result.errors().toString());
                return null;
            });
            return null;
        });
    }

    private ObjectNode baseCharter(String actingAs, String serviceAccountId) {
        ObjectNode charter = mapper.createObjectNode();
        charter.put("role", "应收对账专员");
        charter.put("departmentId", "finance");
        charter.put("mission", "对账与核销");
        charter.put("actingAs", actingAs);
        if (serviceAccountId != null) {
            charter.put("serviceAccountId", serviceAccountId);
        }
        charter.putArray("actionClasses");
        charter.putArray("forbiddenTools");
        charter.putObject("escalation").put("type", "OWNER");
        charter.putArray("kpis");
        charter.put("reviewEvery", "P90D");
        return charter;
    }

    private void addActionClass(ObjectNode charter, String code, String name,
                                List<String> patterns, String ceiling) {
        ArrayNode classes = (ArrayNode) charter.get("actionClasses");
        ObjectNode ac = classes.addObject();
        ac.put("code", code);
        ac.put("name", name);
        ArrayNode pats = ac.putArray("toolPatterns");
        patterns.forEach(pats::add);
        ac.putObject("limits");
        ac.put("fourEyes", false);
        ac.put("ceiling", ceiling);
    }

    private String publishPlan(String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);
        return planId;
    }

    private static AgentSpec spec(String name, String planId,
                                  List<AgentSpec.ToolSet.McpBinding> mcp,
                                  ObjectNode charter) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile(name, null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "t"))),
                new AgentSpec.ModelPolicy(planId, false),
                new AgentSpec.ToolSet(List.of(), mcp, List.of(), List.of()),
                List.of(),
                new AgentSpec.KnowledgeBinding(List.of(), "TOOL"),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                charter, null, null, null, null, null, null, null);
    }

    private static Principal adminPrincipal(TestFixtures.Actor a) {
        return new Principal(a.userId(), a.workspaceId(), PrincipalType.USER,
                Set.of("system_admin", "workspace_admin"), Set.of("*"), "test");
    }

    private static Principal builderPrincipal(TestFixtures.Actor a) {
        return new Principal(a.userId(), a.workspaceId(), PrincipalType.USER,
                Set.of("builder"), Set.of(
                "agent:read", "agent:write", "agent:publish", "agent:share",
                "mcp:read", "mcp:write", "kb:read", "kb:write", "kb:share",
                "skill:read", "skill:write", "model:read", "chat:use"), "test");
    }
}
