package com.tioka.server.gate;

import com.tioka.agent.api.AgentSnapshotApi;
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
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.authz.ResourceAclApi;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G1：AST-01、AST-02。用例说明见 docs/architecture/construction/M1-05。
 */
@SpringBootTest
@Tag("gate")
class G1AgentPublishGateIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentService agents;
    @Autowired AgentSnapshotApi snapshots;
    @Autowired ModelAdminService models;
    @Autowired McpAdminService mcpAdmin;
    @Autowired KnowledgeService knowledge;
    @Autowired PrincipalHolder principals;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResourceAclApi resourceAcl;

    /** 发布后修改草稿与 MCP Server 工具白名单，已发布版本的 spec 与哈希不变，进行中的会话仍用原版本 */
    @Test
    void publishedSnapshotIsFrozen() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        principals.runAs(adminPrincipal(admin), () -> {
            String planId = publishPlan("g1-frozen");
            McpStore.McpServer server = mcpAdmin.register("g1-mcp", "mock://x", "t");
            mcpAdmin.refreshTools(server.id());
            resourceAcl.ensureOwner("mcp", server.id(), admin.userId());

            var agent = agents.create("g1-frozen-agent", admin.workspaceId());
            AgentSpec v1Spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "v1"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    new AgentSpec.ToolSet(List.of(),
                            List.of(new AgentSpec.ToolSet.McpBinding(server.id(), List.of("mcp_echo"))),
                            List.of(), List.of()),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                    null, null, null, null);
            AgentService.PublishResult p1 = agents.publishSpec(agent.id(), v1Spec);
            String hash1 = snapshots.get(p1.versionId()).contentHash();

            AgentService.DraftView draft = agents.getDraft(agent.id());
            agents.saveDraft(agent.id(), new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "draft-v2"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    new AgentSpec.ToolSet(List.of(),
                            List.of(new AgentSpec.ToolSet.McpBinding(server.id(), List.of("mcp_echo", "extra"))),
                            List.of(), List.of()),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                    null, null, null, null), draft.revision());

            assertEquals(hash1, snapshots.get(p1.versionId()).contentHash());
            assertEquals("v1", snapshots.get(p1.versionId()).spec().instructions().sections().get(0).text());
            assertEquals(List.of("mcp_echo"),
                    snapshots.get(p1.versionId()).spec().tools().mcp().get(0).allowlist());
            return null;
        });
    }

    /** builder 引用无 USE 权限的知识库发布，返回 422 且 errors 含 REF_FORBIDDEN */
    @Test
    void publisherWithoutUseIsRejected() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "builder");
        TestFixtures.Actor builder = fixtures.newLimitedUser(admin, "builder");

        String planId = principals.runAs(adminPrincipal(admin), () -> publishPlan("g1-forbid"));
        String kbId = principals.runAs(builderPrincipal(owner), () -> {
            var kb = knowledge.createBase(admin.workspaceId(), "g1-priv-kb");
            jdbc.update("UPDATE kb_base SET visibility='PRIVATE', owner_id=? WHERE id=?",
                    owner.userId(), kb.id());
            resourceAcl.ensureOwner("kb", kb.id(), owner.userId());
            return kb.id();
        });

        principals.runAs(builderPrincipal(builder), () -> {
            var agent = agents.create("g1-forbid-agent", admin.workspaceId());
            AgentSpec spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "t"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    AgentSpec.ToolSet.empty(),
                    List.of(),
                    new AgentSpec.KnowledgeBinding(List.of(kbId), "TOOL"),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                    null, null, null, null);
            agents.saveDraft(agent.id(), spec, agents.getDraft(agent.id()).revision());
            BizException ex = assertThrows(BizException.class,
                    () -> agents.publish(agent.id(), agents.getDraft(agent.id()).revision(), null));
            assertEquals(ErrorCode.AGENT_PUBLISH_INVALID, ex.getErrorCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) ex.getData();
            assertNotNull(data);
            @SuppressWarnings("unchecked")
            List<Object> errors = (List<Object>) data.get("errors");
            assertNotNull(errors);
            assertTrue(errors.stream().anyMatch(e -> e.toString().contains("REF_FORBIDDEN")),
                    () -> String.valueOf(errors));
            return null;
        });
    }

    private String publishPlan(String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);
        return planId;
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
