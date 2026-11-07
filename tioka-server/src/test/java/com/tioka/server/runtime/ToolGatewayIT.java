package com.tioka.server.runtime;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
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
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.tool.ToolRpmLimiter;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysResourceAclRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CON-01：工具网关鉴权、限流、审计（M1-04）。
 */
@SpringBootTest
@Tag("it")
class ToolGatewayIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired ToolGateway gateway;
    @Autowired ToolRpmLimiter rpmLimiter;
    @Autowired AuditApi audit;
    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired PrincipalLoader principalLoader;
    @Autowired McpAdminService mcpAdmin;
    @Autowired AgentService agents;
    @Autowired AgentSnapshotApi snapshots;
    @Autowired ModelAdminService models;
    @Autowired KnowledgeService knowledge;
    @Autowired SysResourceAclRepository acls;
    @Autowired SysMemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void unauthorizedMcpDeniedAndAudited() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");

        String versionId = principals.runAs(adminPrincipal(admin), () -> {
            String planId = publishPlan("deny");
            McpStore.McpServer server = mcpAdmin.register("con01-deny", "mock://local", null);
            mcpAdmin.refreshTools(server.id());
            jdbc.update("UPDATE mcp_server SET visibility = 'PRIVATE' WHERE id = ?", server.id());
            AgentStore.Agent agent = agents.create("con01-mcp-deny", admin.workspaceId());
            AgentSpec spec = minimalSpec(agent.name(), planId, List.of(
                    new AgentSpec.ToolSet.McpBinding(server.id(), List.of("mcp_echo"))));
            return agents.publishSpec(agent.id(), spec).versionId();
        });

        Map<String, Object> toolCfg = principals.runAs(adminPrincipal(admin), () -> mcpBindingsOf(versionId));
        ToolProvider.ToolResult result = principals.runAs(loaded(member), () ->
                gateway.call(ctx(loaded(member), versionId, toolCfg),
                        "mcp_echo", Map.of("text", "x")));

        assertFalse(result.ok());
        assertTrue(result.content().contains("无权调用工具"), result.content());

        List<AuditEvent> denied = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), member.userId(), "tool.invoke", "tool", "mcp_echo",
                AuditOutcome.DENIED, null, null, null, 20));
        assertFalse(denied.isEmpty(), "应有 tool.invoke DENIED 审计");
    }

    @Test
    void kbArgsFilteredByAcl() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");

        String[] ids = principals.runAs(adminPrincipal(admin), () -> {
            String embed = seedEmbeddingBinding();
            var allowed = knowledge.createBase(admin.workspaceId(), "kb-allow", embed);
            var denied = knowledge.createBase(admin.workspaceId(), "kb-deny", embed);
            jdbc.update("UPDATE kb_base SET visibility = 'PRIVATE' WHERE id IN (?, ?)",
                    allowed.id(), denied.id());
            acls.upsert("kb", allowed.id(), "user", member.userId(), "USE");
            knowledge.ingest(allowed.id(), "a", "allowed-doc-token unique-aaa", "upload");
            knowledge.ingest(denied.id(), "b", "denied-doc-token unique-bbb", "upload");
            return new String[]{allowed.id(), denied.id()};
        });

        ToolProvider.ToolResult result = principals.runAs(loaded(member), () ->
                gateway.call(ctx(loaded(member), "av-kb", Map.of()),
                        "kb.search", Map.of(
                                "kbIds", List.of(ids[0], ids[1]),
                                "query", "unique",
                                "topK", 5)));

        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("unique-aaa"), result.content());
        assertFalse(result.content().contains("unique-bbb"),
                "无权知识库内容不应出现: " + result.content());
    }

    @Test
    void revokedPermissionTakesEffectWithin60s() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor builder = fixtures.newLimitedUser(admin, "builder");

        String kbId = principals.runAs(adminPrincipal(admin), () -> {
            String embed = seedEmbeddingBinding();
            var kb = knowledge.createBase(admin.workspaceId(), "kb-revoke", embed);
            jdbc.update("UPDATE kb_base SET visibility = 'WORKSPACE' WHERE id = ?", kb.id());
            knowledge.ingest(kb.id(), "d", "revoke-token-zzz", "upload");
            return kb.id();
        });

        Principal before = loaded(builder);
        assertTrue(before.hasPermission("kb:read"));
        ToolProvider.ToolResult ok = principals.runAs(before, () ->
                gateway.call(ctx(before, "av-rev", Map.of()),
                        "kb.search", Map.of("kbId", kbId, "query", "revoke-token-zzz", "topK", 3)));
        assertTrue(ok.ok(), ok.content());

        // 多角色主键：必须 replace，不能只 upsert（否则仍保留 builder）
        members.replaceWorkspaceRole(admin.workspaceId(), builder.userId(), "viewer");
        int removed = jdbc.update(
                "DELETE FROM sys_role_permission WHERE role_code = 'viewer' AND permission = 'kb:read'");
        assertTrue(removed >= 1);
        try {
            // 模拟 Principal 60s 缓存过期（ADR-029 第 6 条）；key 含 authMethod（password）
            redis.delete("auth:principal:" + builder.userId() + ":" + admin.workspaceId() + ":password");
            Principal after = principalLoader.load(Long.parseLong(builder.userId()), admin.workspaceId());
            assertFalse(after.hasPermission("kb:read"),
                    "收回后应无 kb:read，实际=" + after.permissions());

            ToolProvider.ToolResult denied = principals.runAs(after, () ->
                    gateway.call(ctx(after, "av-rev", Map.of()),
                            "kb.search", Map.of("kbId", kbId, "query", "revoke-token-zzz", "topK", 3)));
            assertFalse(denied.ok());
            assertTrue(denied.content().contains("无权") || denied.content().contains("kb:read"),
                    denied.content());
        } finally {
            jdbc.update("""
                    INSERT INTO sys_role_permission (role_code, permission)
                    VALUES ('viewer', 'kb:read')
                    ON CONFLICT (role_code, permission) DO NOTHING
                    """);
        }
    }

    @Test
    void rateLimited() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        Principal p = loaded(admin);
        rpmLimiter.setLimit("tool", "echo", 2);
        try {
            principals.runAs(p, () -> {
                assertTrue(gateway.call(ctx(p, "av-rl", Map.of()), "echo", Map.of("text", "1")).ok());
                assertTrue(gateway.call(ctx(p, "av-rl", Map.of()), "echo", Map.of("text", "2")).ok());
                BizException ex = assertThrows(BizException.class, () ->
                        gateway.call(ctx(p, "av-rl", Map.of()), "echo", Map.of("text", "3")));
                assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
                return null;
            });
        } finally {
            rpmLimiter.setLimit("tool", "echo", null);
        }
    }

    @Test
    void schemaValidation() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        Principal p = loaded(admin);
        ToolProvider.ToolResult r = principals.runAs(p, () ->
                gateway.call(ctx(p, "av-schema", Map.of()), "echo", Map.of()));
        assertFalse(r.ok());
        assertTrue(r.content().toLowerCase().contains("schema")
                        || r.content().contains("参数"),
                r.content());
    }

    private Map<String, Object> mcpBindingsOf(String versionId) {
        AgentSpec.ToolSet tools = snapshots.get(versionId).spec().tools();
        if (tools == null || tools.mcp() == null) {
            return Map.of("mcp", List.of());
        }
        List<Map<String, Object>> grants = tools.mcp().stream()
                .map(b -> Map.<String, Object>of(
                        "serverId", b.serverId(),
                        "allowlist", b.allowlist() == null ? List.of() : b.allowlist()))
                .toList();
        return Map.of("mcp", grants);
    }

    private String publishPlan(String tag) {
        String id = Ids.newId(tag);
        var provider = models.createProvider(new CreateProviderRequest(id + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(id + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(id + "-rp", "FIXED", binding)).id();
        models.publish(planId);
        return planId;
    }

    /** KNW-01：知识入库须绑定 kind=embedding 且维度与 tioka.knowledge.embedding-dim 一致。 */
    private String seedEmbeddingBinding() {
        String id = Ids.newId("emb");
        var provider = models.createProvider(new CreateProviderRequest(id + "-p", "mock://openai", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(id + "-m", "embed"));
        return models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "text-embedding", "embedding", 32));
    }

    private ToolGateway.ToolCallContext ctx(Principal p, String agentVersionId,
                                            Map<String, Object> agentToolConfig) {
        return ToolCallContexts.forTurn(p, "thr-con01", "turn-con01", agentVersionId,
                "chat", List.of(), agentToolConfig);
    }

    private Principal loaded(TestFixtures.Actor a) {
        return principalLoader.load(Long.parseLong(a.userId()), a.workspaceId());
    }

    private static Principal adminPrincipal(TestFixtures.Actor admin) {
        return new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("system_admin", "workspace_admin"), Set.of("*"), "password");
    }

    private static AgentSpec minimalSpec(String name, String planId,
                                         List<AgentSpec.ToolSet.McpBinding> mcp) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile(name, null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(
                        new AgentSpec.Instructions.Section("main", "test"))),
                new AgentSpec.ModelPolicy(planId, false),
                new AgentSpec.ToolSet(List.of("echo"), mcp, List.of(), List.of()),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null);
    }
}
