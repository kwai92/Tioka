package com.tioka.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import com.tioka.model.api.dto.ProviderDtos.CreateBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class PublishValidatorIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired McpAdminService mcpAdmin;
    @Autowired KnowledgeService knowledge;
    @Autowired PrincipalHolder principals;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired SysResourceAclRepository acls;
    @Autowired ResourceAclApi resourceAcl;
    @Autowired ObjectMapper mapper;

    @Test
    void collectsAllErrors() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor builder = fixtures.newLimitedUser(admin, "builder");

        principals.runAs(adminPrincipal(admin), () -> {
            McpStore.McpServer server = mcpAdmin.register("priv-mcp", "mock://x", "t");
            mcpAdmin.refreshTools(server.id());
            jdbc.update("UPDATE mcp_server SET visibility='PRIVATE', owner_id=? WHERE id=?",
                    admin.userId(), server.id());
            acls.upsert("mcp", server.id(), "user", admin.userId(), "OWNER");

            principals.runAs(builderPrincipal(builder), () -> {
                var agent = agents.create("collect-err", admin.workspaceId());
                AgentSpec bad = spec(agent.name(), "rp_missing",
                        List.of(new AgentSpec.ToolSet.McpBinding(server.id(), List.of())),
                        List.of(), null);
                agents.saveDraft(agent.id(), bad, agents.getDraft(agent.id()).revision());
                AgentService.ValidateResult result = agents.validate(agent.id());
                assertFalse(result.ok());
                Set<String> codes = result.errors().stream()
                        .map(e -> e.code()).collect(Collectors.toSet());
                assertTrue(codes.contains("REF_NOT_FOUND"), () -> result.errors().toString());
                assertTrue(codes.contains("REF_FORBIDDEN"), () -> result.errors().toString());
                assertEquals(2, result.errors().size(), () -> result.errors().toString());
                return null;
            });
            return null;
        });
    }

    @Test
    void publisherNeedsUse() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "builder");
        TestFixtures.Actor builder = fixtures.newLimitedUser(admin, "builder");

        String planId = principals.runAs(adminPrincipal(admin), () -> publishPlan("use"));
        String kbId = principals.runAs(ownerPrincipal(owner), () -> {
            var kb = knowledge.createBase(admin.workspaceId(), "secret-kb");
            jdbc.update("UPDATE kb_base SET visibility='PRIVATE', owner_id=? WHERE id=?",
                    owner.userId(), kb.id());
            resourceAcl.ensureOwner("kb", kb.id(), owner.userId());
            return kb.id();
        });

        principals.runAs(builderPrincipal(builder), () -> {
            var agent = agents.create("need-use", admin.workspaceId());
            AgentSpec withKb = spec(agent.name(), planId, List.of(), List.of(kbId), null);
            agents.saveDraft(agent.id(), withKb, agents.getDraft(agent.id()).revision());
            AgentService.ValidateResult denied = agents.validate(agent.id());
            assertFalse(denied.ok());
            assertTrue(denied.errors().stream().anyMatch(e -> "REF_FORBIDDEN".equals(e.code())),
                    () -> denied.errors().toString());

            principals.runAs(ownerPrincipal(owner), () -> {
                acls.upsert("kb", kbId, "user", builder.userId(), "USE");
                return null;
            });

            AgentService.ValidateResult ok = agents.validate(agent.id());
            assertTrue(ok.ok(), () -> ok.errors().toString());
            AgentService.PublishResult published = agents.publish(
                    agent.id(), agents.getDraft(agent.id()).revision(), null);
            assertNotNull(published.versionId());
            return null;
        });
    }

    @Test
    void reservedFieldRejected() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String planId = principals.runAs(adminPrincipal(admin), () -> publishPlan("rsv"));
        principals.runAs(adminPrincipal(admin), () -> {
            var agent = agents.create("reserved", admin.workspaceId());
            ObjectNode charter = mapper.createObjectNode().put("x", 1);
            AgentSpec bad = spec(agent.name(), planId, List.of(), List.of(), charter);
            agents.saveDraft(agent.id(), bad, agents.getDraft(agent.id()).revision());
            AgentService.ValidateResult result = agents.validate(agent.id());
            assertFalse(result.ok());
            assertTrue(result.errors().stream().anyMatch(e -> "RESERVED_FIELD".equals(e.code())),
                    () -> result.errors().toString());
            return null;
        });
    }

    @Test
    void rollbackRevalidates() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        principals.runAs(adminPrincipal(admin), () -> {
            String planId = publishPlan("rbv");
            McpStore.McpServer server = mcpAdmin.register("rbv-mcp", "mock://x", "t");
            mcpAdmin.refreshTools(server.id());
            resourceAcl.ensureOwner("mcp", server.id(), admin.userId());

            var agent = agents.create("rbv-agent", admin.workspaceId());
            AgentSpec withMcp = spec(agent.name(), planId,
                    List.of(new AgentSpec.ToolSet.McpBinding(server.id(), List.of("mcp_echo"))),
                    List.of(), null);
            AgentService.PublishResult v1 = agents.publishSpec(agent.id(), withMcp);
            AgentService.PublishResult v2 = agents.publishSpec(agent.id(),
                    spec(agent.name(), planId, List.of(), List.of(), null));
            assertEquals(v2.versionId(), agents.get(agent.id()).publishedVersionId());

            jdbc.update("UPDATE mcp_server SET deleted = TRUE WHERE id = ?", server.id());

            BizException ex = assertThrows(BizException.class,
                    () -> agents.rollback(agent.id(), v1.versionId()));
            assertEquals(ErrorCode.AGENT_PUBLISH_INVALID, ex.getErrorCode());
            return null;
        });
    }

    private String publishPlan(String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);
        return planId;
    }

    private static AgentSpec spec(String name, String planId,
                                  List<AgentSpec.ToolSet.McpBinding> mcp,
                                  List<String> kbIds,
                                  ObjectNode charter) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile(name, null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "t"))),
                new AgentSpec.ModelPolicy(planId, false),
                new AgentSpec.ToolSet(List.of(), mcp, List.of(), List.of()),
                List.of(),
                new AgentSpec.KnowledgeBinding(kbIds, "TOOL"),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                charter, null, null, null);
    }

    private static Principal adminPrincipal(TestFixtures.Actor a) {
        return new Principal(a.userId(), a.workspaceId(), PrincipalType.USER,
                Set.of("tenant_admin", "workspace_admin"), Set.of("*"), "test");
    }

    private static Principal builderPrincipal(TestFixtures.Actor a) {
        return new Principal(a.userId(), a.workspaceId(), PrincipalType.USER,
                Set.of("builder"), Set.of(
                "agent:read", "agent:write", "agent:publish", "agent:share",
                "mcp:read", "mcp:write", "kb:read", "kb:write", "kb:share",
                "skill:read", "skill:write", "model:read", "chat:use"), "test");
    }

    private static Principal ownerPrincipal(TestFixtures.Actor a) {
        return builderPrincipal(a);
    }
}
