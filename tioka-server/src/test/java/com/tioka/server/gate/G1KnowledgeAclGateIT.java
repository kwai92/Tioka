package com.tioka.server.gate;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.UsageRecorder;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.LlmStubServer;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.UserAdminService;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysResourceAclRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G1：KNW-01、KNW-02。用例说明见 docs/architecture/construction/M1-04、M1-03。
 *
 * <p>运行环境：DualInstance + Testcontainers（PostgreSQL、Redis）+ LLM 桩（含 /embeddings）。
 */
@Tag("gate")
class G1KnowledgeAclGateIT {

    /** Turn 中 kb.search 参数含无权知识库：只检索有权知识库，结果不含受限文档，审计有 tool.invoke */
    @Test
    void restrictedKbNotRetrievedInTurn() {
        try (DualInstance di = DualInstance.start("kb-search", Map.of(), false)) {
            ConfigurableApplicationContext ctx = di.a();
            PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
            KnowledgeService knowledge = ctx.getBean(KnowledgeService.class);
            ModelAdminService models = ctx.getBean(ModelAdminService.class);
            SysResourceAclRepository acls = ctx.getBean(SysResourceAclRepository.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            AuditApi audit = ctx.getBean(AuditApi.class);
            PrincipalLoader principalLoader = ctx.getBean(PrincipalLoader.class);
            ItemRepository items = ctx.getBean(ItemRepository.class);

            GateSeed.Bundle adminBundle = seedAgentForKb(ctx, di.llm().baseUrl());
            Member member = createMember(ctx, adminBundle);

            String[] kbIds = principals.runAs(GateSeed.user(adminBundle.userId(), adminBundle.workspaceId()), () -> {
                String embed = seedEmbeddingBinding(models, di.llm().baseUrl());
                var allowed = knowledge.createBase(adminBundle.workspaceId(), "g1-kb-allow", embed);
                var denied = knowledge.createBase(adminBundle.workspaceId(), "g1-kb-deny", embed);
                jdbc.update("UPDATE kb_base SET visibility = 'PRIVATE' WHERE id IN (?, ?)",
                        allowed.id(), denied.id());
                acls.upsert("kb", allowed.id(), "user", member.userId(), "USE");
                knowledge.ingest(allowed.id(), "a", "g1-allowed-token-aaa policy", "upload");
                knowledge.ingest(denied.id(), "b", "g1-denied-token-bbb policy", "upload");
                return new String[]{allowed.id(), denied.id()};
            });

            injectKbSearchScript(di.llm(), kbIds[0], kbIds[1]);

            Principal memberP = principalLoader.load(Long.parseLong(member.userId()), adminBundle.workspaceId());
            GateSeed.Bundle memberBundle = new GateSeed.Bundle(
                    member.userId(), adminBundle.workspaceId(), member.token(),
                    adminBundle.agentVersionId(), adminBundle.routingPlanId());

            var thr = GateSeed.createThread(ctx, memberBundle);
            var handle = GateSeed.startTurn(ctx, memberBundle, thr.threadId(),
                    "cm-g1-kb-acl", "search policy");
            var done = GateSeed.awaitTerminal(ctx, memberBundle, handle.turnId(), 90_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

            List<RuntimeItemDO> toolResults = items.listByThread(thr.threadId()).stream()
                    .filter(i -> ItemType.TOOL_RESULT.wire().equals(i.getType()))
                    .toList();
            assertFalse(toolResults.isEmpty(), "Turn 应有 tool_result");
            String toolOut = toolResults.stream()
                    .map(RuntimeItemDO::getContentJson)
                    .filter(c -> c != null)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(toolOut.contains("g1-allowed-token-aaa"),
                    "tool_result 应命中有权文档: " + toolOut);
            assertFalse(toolOut.contains("g1-denied-token-bbb"),
                    "tool_result 不得含无权知识库文档: " + toolOut);

            List<AuditEvent> invokes = audit.query(new AuditApi.AuditQuery(
                    adminBundle.workspaceId(), member.userId(), "tool.invoke", "tool", "kb.search",
                    AuditOutcome.SUCCESS, null, null, null, 20));
            assertFalse(invokes.isEmpty(), "审计应有 tool.invoke SUCCESS: " + invokes);
            assertEquals(member.userId(), memberP.userId());
        }
    }

    /** 知识库 ingest 与检索的嵌入调用都有 model_usage 记录，purpose=EMBEDDING */
    @Test
    void embeddingGoesThroughGateway() {
        try (DualInstance di = DualInstance.start("kb-search", Map.of(), false)) {
            ConfigurableApplicationContext ctx = di.a();
            PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
            KnowledgeService knowledge = ctx.getBean(KnowledgeService.class);
            ModelAdminService models = ctx.getBean(ModelAdminService.class);
            UsageRecorder usageRecorder = ctx.getBean(UsageRecorder.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            GateSeed.Bundle admin = GateSeed.seedPublishedAgent(ctx, di.llm().baseUrl());
            principals.runAs(GateSeed.user(admin.userId(), admin.workspaceId()), () -> {
                String embed = seedEmbeddingBinding(models, di.llm().baseUrl());
                var kb = knowledge.createBase(admin.workspaceId(), "g1-embed-kb", embed);
                knowledge.ingest(kb.id(), "d", "g1-embed-ingest-token", "upload");
                knowledge.retrieve(List.of(kb.id()), "g1-embed-ingest-token", 3);
                return null;
            });
            usageRecorder.flush();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    SELECT purpose, status, binding_id FROM model_usage
                    WHERE workspace_id = ? AND purpose = 'EMBEDDING'
                    """,
                    admin.workspaceId());
            assertFalse(rows.isEmpty(), "ingest/检索须经 ModelGateway 记账 EMBEDDING");
            assertTrue(rows.stream().allMatch(r -> "SUCCESS".equals(String.valueOf(r.get("status")))),
                    "EMBEDDING usage 应为 SUCCESS: " + rows);
            assertTrue(rows.size() >= 2,
                    "ingest 与检索至少各一条 EMBEDDING: " + rows);
        }
    }

    private static GateSeed.Bundle seedAgentForKb(ConfigurableApplicationContext ctx, String llmBaseUrl) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        ModelAdminService models = ctx.getBean(ModelAdminService.class);
        AgentService agents = ctx.getBean(AgentService.class);
        com.tioka.system.application.AuthService auth = ctx.getBean(com.tioka.system.application.AuthService.class);

        var login = auth.login("admin", com.tioka.server.support.IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD, "127.0.0.1");
        String userId = login.user().id();
        String ws = login.workspaces().isEmpty() ? "ws_default" : login.workspaces().get(0).id();

        return principals.runAs(GateSeed.user(userId, ws), () -> {
            String tag = Ids.newId("g1kb");
            var provider = models.createProvider(new CreateProviderRequest(tag + "-llm", llmBaseUrl, "sk"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
            String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-stub"));
            String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
            models.publish(planId);

            AgentStore.Agent agent = agents.create(tag + "-agent", ws);
            AgentSpec spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(
                            new AgentSpec.Instructions.Section("main", "you call kb.search"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    new AgentSpec.ToolSet(List.of(), List.of(), List.of(), List.of()),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                null, null, null, null, null, null, null, null);
            AgentService.PublishResult published = agents.publishSpec(agent.id(), spec);
            return new GateSeed.Bundle(userId, ws, login.accessToken(), published.versionId(), planId);
        });
    }

    private static String seedEmbeddingBinding(ModelAdminService models, String llmBaseUrl) {
        String id = Ids.newId("emb");
        var provider = models.createProvider(new CreateProviderRequest(id + "-p", llmBaseUrl, "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(id + "-m", "embed"));
        return models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "text-embedding", "embedding", 32));
    }

    private static void injectKbSearchScript(LlmStubServer llm, String allowedKbId, String deniedKbId) {
        LlmStubServer.Script script = new LlmStubServer.Script();
        LlmStubServer.Call call1 = new LlmStubServer.Call();
        LlmStubServer.ToolCallSpec tc = new LlmStubServer.ToolCallSpec();
        tc.name = "kb.search";
        tc.arguments = Map.of(
                "kbIds", List.of(allowedKbId, deniedKbId),
                "query", "policy",
                "topK", 5);
        call1.tool_calls = List.of(tc);
        call1.usage = usage(10, 6);
        LlmStubServer.Call call2 = new LlmStubServer.Call();
        call2.content = "answer from kb";
        call2.stream_chunk_chars = 5;
        call2.usage = usage(14, 16);
        script.calls = new ArrayList<>(List.of(call1, call2));
        llm.putScript("kb-search", script);
    }

    private static LlmStubServer.UsageSpec usage(int prompt, int completion) {
        LlmStubServer.UsageSpec u = new LlmStubServer.UsageSpec();
        u.prompt = prompt;
        u.completion = completion;
        return u;
    }

    private static Member createMember(ConfigurableApplicationContext ctx, GateSeed.Bundle admin) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        UserAdminService users = ctx.getBean(UserAdminService.class);
        SysMemberRepository members = ctx.getBean(SysMemberRepository.class);
        com.tioka.system.application.AuthService auth = ctx.getBean(com.tioka.system.application.AuthService.class);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "g1kb-" + suffix;
        String password = "LimitedPass!23456";
        UserAdminService.CreatedUser created = principals.runAs(
                new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                        Set.of("system_admin"), Set.of("*"), "password"),
                () -> users.create(username, "g1-kb-member", password));
        members.upsert(admin.workspaceId(), created.id(), "member");
        var login = auth.login(username, password, "127.0.0.1");
        return new Member(created.id(), login.accessToken());
    }

    private record Member(String userId, String token) {}
}
