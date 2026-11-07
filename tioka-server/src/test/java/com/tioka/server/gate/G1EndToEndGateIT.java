package com.tioka.server.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.UsageRecorder;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.gate.support.KeycloakLogin;
import com.tioka.server.gate.support.SseCollector;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.QuotaService;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.application.quota.DefaultQuotaGuard;
import com.tioka.system.application.quota.QuotaMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G1：34 号计划 G1 全链路（M1-05 G1-E2E 第 1〜8 步）。
 *
 * <p>运行环境：GateCluster（3 实例）+ Testcontainers（PostgreSQL、Redis、Keycloak）+ LLM 桩 + MCP 桩（ENG-04b）。
 */
@Tag("gate")
class G1EndToEndGateIT {
    private static final String WS_SALES = "ws_sales";
    /** mcp-three-steps.yaml：4 次调用 usage 之和。 */
    private static final long MCP_THREE_STEPS_TOKENS = 12L + 8L + 14L + 8L + 16L + 8L + 20L + 24L;
    /** mcp-slow-cancel.yaml：工具调用前模型返回的 usage。 */
    private static final long MCP_SLOW_CANCEL_TOKENS = 10L + 5L;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Keycloak 登录 → 选已发布 Agent → 3 步 MCP 工具调用 → 另一实例 SSE 流式 → 另一实例取消 → 用量计入空间配额 → 审计可查 */
    @Test
    void keycloakLoginPublishedAgentMcpToolsQuotaAudit() throws Exception {
        try (GateCluster cluster = GateCluster.start(3, "mcp-three-steps", Map.of(), true)) {
            ensureWsSales(cluster.a());
            clearWorkspaceQuota(cluster.a(), WS_SALES);

            // —— 1. alice Keycloak 登录（实例 A）——
            KeycloakLogin loginA = new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0));
            String aliceToken = loginA.login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
            assertTrue(aliceToken.startsWith("tka_"), aliceToken);

            HttpResponse<String> aliceMe = get(cluster.baseUrl(0) + "/api/system/auth/me", aliceToken, "ws_default");
            assertEquals(200, aliceMe.statusCode(), aliceMe.body());
            JsonNode aliceMeData = json.readTree(aliceMe.body()).path("data");
            assertEquals("alice", aliceMeData.path("user").path("username").asText());
            boolean hasSystemAdmin = false;
            for (JsonNode r : aliceMeData.path("roles")) {
                if ("system_admin".equals(r.asText())) {
                    hasSystemAdmin = true;
                    break;
                }
            }
            assertTrue(hasSystemAdmin, " /me 应含 system_admin，body=" + aliceMe.body());

            JdbcTemplate jdbc = cluster.a().getBean(JdbcTemplate.class);
            Long aliceUserId = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, "alice");
            assertNotNull(aliceUserId);

            // —— 2. alice 创建并发布 Agent（路由桩 + erp.query/update + toolPolicies.erp.update=auto）——
            PrincipalHolder principals = cluster.a().getBean(PrincipalHolder.class);
            Principal aliceP = cluster.a().getBean(PrincipalLoader.class).load(aliceUserId, WS_SALES);
            PublishedAgent published = principals.runAs(aliceP, () ->
                    publishErpAgent(cluster.a(), cluster.llm().baseUrl(), cluster.mcp().baseUrl(),
                            WS_SALES, true));
            assertEquals(1, published.versionNo());

            List<AuditEvent> publishAudit = cluster.a().getBean(AuditApi.class).query(new AuditApi.AuditQuery(
                    WS_SALES, String.valueOf(aliceUserId), "agent.publish", "agent", published.agentId(),
                    AuditOutcome.SUCCESS, null, null, null, 20));
            assertFalse(publishAudit.isEmpty(), "审计应有 agent.publish");

            // —— 3. 空间配额 + bob 登录（实例 B）——
            principals.runAs(aliceP, () -> {
                cluster.a().getBean(QuotaService.class).putLimit(
                        QuotaMetrics.SCOPE_WORKSPACE, WS_SALES, QuotaMetrics.TOKENS_MONTH, 1_000_000L);
                return null;
            });

            KeycloakLogin loginB = new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(1));
            String bobToken = loginB.login(KeycloakGateSupport.USER_BOB, KeycloakGateSupport.PASSWORD_BOB);
            assertTrue(bobToken.startsWith("tka_"), bobToken);

            HttpResponse<String> bobMe = get(cluster.baseUrl(1) + "/api/system/auth/me", bobToken, WS_SALES);
            assertEquals(200, bobMe.statusCode(), bobMe.body());
            JsonNode bobMeData = json.readTree(bobMe.body()).path("data");
            assertEquals("bob", bobMeData.path("user").path("username").asText());
            boolean bobMember = false;
            for (JsonNode w : bobMeData.path("workspaces")) {
                if (WS_SALES.equals(w.path("id").asText()) && "member".equals(w.path("role").asText())) {
                    bobMember = true;
                    break;
                }
            }
            assertTrue(bobMember, "bob 应是 ws_sales 的 member，body=" + bobMe.body());

            Long bobUserId = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, "bob");
            assertNotNull(bobUserId);

            // —— 4. bob 会话发送 mcp-three-steps；实例 C 订阅 SSE ——
            cluster.mcp().clearCalls();
            HttpResponse<String> created = postJson(
                    cluster.baseUrl(1) + "/api/chat/conversations",
                    bobToken, WS_SALES,
                    "{\"title\":\"g1-e2e\",\"agentVersionId\":\"" + published.versionId() + "\"}");
            assertEquals(200, created.statusCode(), created.body());
            String convId = json.readTree(created.body()).path("data").path("id").asText();
            String threadId = json.readTree(created.body()).path("data").path("threadId").asText();
            assertFalse(convId.isBlank());
            assertFalse(threadId.isBlank());

            try (SseCollector sse = SseCollector.subscribe(
                    cluster.baseUrl(2), bobToken, WS_SALES, threadId, null)) {
                long t0 = System.nanoTime();
                HttpResponse<String> sent = postJson(
                        cluster.baseUrl(1) + "/api/chat/conversations/" + convId + "/messages",
                        bobToken, WS_SALES,
                        "{\"text\":\"please use mcp tools\",\"clientMsgId\":\"cm-g1-e2e-1\"}");
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                assertEquals(202, sent.statusCode(), sent.body());
                assertTrue(elapsedMs < 500, "HTTP 202 应 < 500ms，elapsedMs=" + elapsedMs);
                String turnId = json.readTree(sent.body()).path("data").path("turnId").asText();

                GateSeed.Bundle bobBundle = new GateSeed.Bundle(
                        String.valueOf(bobUserId), WS_SALES, bobToken,
                        published.versionId(), published.planId());
                RuntimeApi.TurnView done = GateSeed.awaitTerminal(cluster.instance(2), bobBundle, turnId, 90_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

                awaitSseItemType(sse, ItemType.TOOL_CALL.wire(), 3, 60_000);
                awaitSseItemType(sse, ItemType.TOOL_RESULT.wire(), 3, 60_000);
                awaitSseItemType(sse, ItemType.MESSAGE.wire(), 1, 30_000);

                assertEquals(3, cluster.mcp().calls().size(),
                        "MCP 桩应记录 3 次调用，calls=" + cluster.mcp().calls());

                // —— 5. bob 再发 erp.slow；实例 A /stop ——
                System.setProperty("tioka.llm.script", "mcp-slow-cancel");
                cluster.llm().resetCounter("mcp-slow-cancel");

                HttpResponse<String> sent2 = postJson(
                        cluster.baseUrl(1) + "/api/chat/conversations/" + convId + "/messages",
                        bobToken, WS_SALES,
                        "{\"text\":\"please call slow\",\"clientMsgId\":\"cm-g1-e2e-2\"}");
                assertEquals(202, sent2.statusCode(), sent2.body());
                String turn2 = json.readTree(sent2.body()).path("data").path("turnId").asText();

                waitUntilToolCall(cluster.a(), threadId, 30_000);

                HttpResponse<String> stop = postJson(
                        cluster.baseUrl(0) + "/api/chat/conversations/" + convId + "/stop",
                        bobToken, WS_SALES, "{}");
                assertEquals(200, stop.statusCode(), stop.body());

                RuntimeApi.TurnView cancelled = GateSeed.awaitStatus(
                        cluster.a(), bobBundle, turn2, 60_000, TurnStatus.CANCELLED);
                assertEquals(TurnStatus.CANCELLED, cancelled.status());
                sse.awaitEvent(RuntimeEventType.TURN_CANCELLED.wire(), 30_000);

                // —— 6. 配额 used = 桩 usage 之和 ——
                cluster.a().getBean(UsageRecorder.class).flush();
                long expected = MCP_THREE_STEPS_TOKENS + MCP_SLOW_CANCEL_TOKENS;
                long redisUsed = cluster.a().getBean(DefaultQuotaGuard.class)
                        .usedTokens(QuotaMetrics.SCOPE_WORKSPACE, WS_SALES);
                Long dbSum = jdbc.queryForObject(
                        """
                                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0)
                                  FROM model_usage WHERE workspace_id = ?
                                """,
                        Long.class, WS_SALES);
                assertNotNull(dbSum);
                assertEquals(expected, redisUsed, "Redis tokens_month.used 应等于桩 usage 之和");
                assertEquals(redisUsed, dbSum, "Redis 实时值应与 model_usage 之和相等");

                HttpResponse<String> quotas = get(
                        cluster.baseUrl(0) + "/api/system/quotas?scopeType=workspace&scopeId=" + WS_SALES,
                        aliceToken, WS_SALES);
                assertEquals(200, quotas.statusCode(), quotas.body());
                long apiUsed = -1;
                for (JsonNode q : json.readTree(quotas.body()).path("data")) {
                    if (QuotaMetrics.TOKENS_MONTH.equals(q.path("metric").asText())) {
                        apiUsed = q.path("used").asLong();
                    }
                }
                assertEquals(expected, apiUsed, "GET quotas tokens_month.used 应等于桩 usage 之和");

                // —— 7. alice 查审计 tool.invoke SUCCESS ≥ 3 ——
                HttpResponse<String> auditResp = get(
                        cluster.baseUrl(0) + "/api/system/audit?resourceType=tool&actorId=" + bobUserId
                                + "&outcome=SUCCESS&size=50",
                        aliceToken, WS_SALES);
                assertEquals(200, auditResp.statusCode(), auditResp.body());
                int successInvokes = 0;
                for (JsonNode e : json.readTree(auditResp.body()).path("data")) {
                    if ("tool.invoke".equals(e.path("action").asText())
                            && "SUCCESS".equals(e.path("outcome").asText())) {
                        successInvokes++;
                    }
                }
                assertTrue(successInvokes >= 3,
                        "bob 的 tool.invoke SUCCESS 应 ≥ 3，actual=" + successInvokes
                                + " body=" + auditResp.body());
            } finally {
                System.setProperty("tioka.llm.script", "mcp-three-steps");
            }
        }
    }

    /** 无 MCP 授权的 Agent 调用 erp.update：模型收到拒绝结果，审计 tool.invoke DENIED */
    @Test
    void unauthorizedToolDeniedEndToEnd() throws Exception {
        try (GateCluster cluster = GateCluster.start(1, "reject-then-answer", Map.of(), true)) {
            ensureWsSales(cluster.a());

            KeycloakLogin login = new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0));
            String aliceToken = login.login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
            JdbcTemplate jdbc = cluster.a().getBean(JdbcTemplate.class);
            Long aliceUserId = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, "alice");
            assertNotNull(aliceUserId);

            PrincipalHolder principals = cluster.a().getBean(PrincipalHolder.class);
            PrincipalLoader loader = cluster.a().getBean(PrincipalLoader.class);
            Principal aliceP = loader.load(aliceUserId, WS_SALES);

            PublishedAgent published = principals.runAs(aliceP, () ->
                    publishErpAgent(cluster.a(), cluster.llm().baseUrl(), cluster.mcp().baseUrl(),
                            WS_SALES, true));

            // 将 MCP 设为 PRIVATE，使 member 无 USE（「无 MCP 资源权限」→ 拒绝结果 + DENIED 审计）
            jdbc.update("UPDATE mcp_server SET visibility = 'PRIVATE' WHERE id = ?", published.mcpServerId());

            String bobToken = new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0))
                    .login(KeycloakGateSupport.USER_BOB, KeycloakGateSupport.PASSWORD_BOB);
            Long bobUserId = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, "bob");
            assertNotNull(bobUserId);
            Principal bobP = loader.load(bobUserId, WS_SALES);

            Map<String, Object> toolCfg = Map.of("mcp", List.of(
                    Map.of("serverId", published.mcpServerId(),
                            "allowlist", List.of("erp.query", "erp.update", "erp.slow"))));

            ToolProvider.ToolResult result = principals.runAs(bobP, () ->
                    cluster.a().getBean(ToolGateway.class).call(
                            ToolCallContexts.forTurn(bobP, "thr-g1-e2e", "turn-g1-e2e",
                                    published.versionId(), "chat", List.of(), toolCfg),
                            "erp.update", Map.of("q", "nope")));

            assertFalse(result.ok());
            assertTrue(result.content().contains("无权调用工具"),
                    "模型应收到拒绝结果: " + result.content());

            List<AuditEvent> denied = cluster.a().getBean(AuditApi.class).query(new AuditApi.AuditQuery(
                    WS_SALES, String.valueOf(bobUserId), "tool.invoke", "tool", "erp.update",
                    AuditOutcome.DENIED, null, null, null, 20));
            assertFalse(denied.isEmpty(), "审计应有 tool.invoke DENIED");

            // 避免未使用变量告警（aliceToken 用于确认登录链路可用）
            assertTrue(aliceToken.startsWith("tka_"));
        }
    }

    private record PublishedAgent(String agentId, String versionId, int versionNo,
                                  String planId, String mcpServerId) {}

    private PublishedAgent publishErpAgent(ConfigurableApplicationContext ctx,
                                           String llmBaseUrl,
                                           String mcpBaseUrl,
                                           String workspaceId,
                                           boolean withUpdateAuto) {
        ModelAdminService models = ctx.getBean(ModelAdminService.class);
        AgentService agents = ctx.getBean(AgentService.class);
        McpAdminService mcpAdmin = ctx.getBean(McpAdminService.class);

        String tag = Ids.newId("g1e2e");
        var provider = models.createProvider(new CreateProviderRequest(tag + "-llm", llmBaseUrl, "sk-g1e2e"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-stub"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);

        McpStore.McpServer server = mcpAdmin.register(tag + "-mcp", mcpBaseUrl, "tok");
        mcpAdmin.refreshTools(server.id());
        mcpAdmin.updateToolRisk(server.id(), "erp.query", "READ");
        mcpAdmin.updateToolRisk(server.id(), "erp.update", "WRITE");
        mcpAdmin.updateToolRisk(server.id(), "erp.slow", "READ");

        AgentStore.Agent agent = agents.create(tag + "-agent", workspaceId);
        Map<String, String> policies = withUpdateAuto
                ? Map.of("erp.update", "auto")
                : Map.of();
        AgentSpec spec = new AgentSpec(
                1,
                new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(
                        new AgentSpec.Instructions.Section("main", "you call mcp erp tools"))),
                new AgentSpec.ModelPolicy(planId, false),
                new AgentSpec.ToolSet(List.of(),
                        List.of(new AgentSpec.ToolSet.McpBinding(server.id(),
                                List.of("erp.query", "erp.update", "erp.slow"))),
                        List.of(), List.of()),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                new AgentSpec.ApprovalSettings(policies, null),
                AgentSpec.Limits.defaults(),
                null, null, null, null);
        // 经 Spring 代理调用 publish，避免 publishSpec 自调用绕过 @Audited
        AgentService.DraftView draft = agents.saveDraft(agent.id(), spec, agents.getDraft(agent.id()).revision());
        AgentService.PublishResult pub = agents.publish(agent.id(), draft.revision(), "g1-e2e");
        return new PublishedAgent(agent.id(), pub.versionId(), pub.versionNo(), planId, server.id());
    }

    private static void ensureWsSales(ConfigurableApplicationContext ctx) {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_workspace WHERE id = ?", Integer.class, WS_SALES);
        if (n != null && n == 0) {
            jdbc.update("""
                    INSERT INTO sys_workspace (id, name, owner_user_id, create_at, update_at, create_by, update_by, deleted)
                    VALUES ('ws_sales', 'Sales', 1, now(), now(), 'system', 'system', FALSE)
                    """);
        }
    }

    private static void clearWorkspaceQuota(ConfigurableApplicationContext ctx, String workspaceId) {
        StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
        DefaultQuotaGuard guard = ctx.getBean(DefaultQuotaGuard.class);
        String ym = DefaultQuotaGuard.yearMonth();
        redis.delete(List.of(
                guard.tokKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, ym),
                guard.limKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, QuotaMetrics.TOKENS_MONTH),
                guard.ccKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId)));
        ctx.getBean(JdbcTemplate.class).update(
                "DELETE FROM gov_quota WHERE scope_type='workspace' AND scope_id=?", workspaceId);
        ctx.getBean(JdbcTemplate.class).update(
                "DELETE FROM model_usage WHERE workspace_id=?", workspaceId);
    }

    private void awaitSseItemType(SseCollector sse, String itemType, int minCount, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long n = sse.frames().stream()
                    .filter(f -> RuntimeEventType.ITEM_CREATED.wire().equals(f.event())
                            || RuntimeEventType.ITEM_COMPLETED.wire().equals(f.event()))
                    .filter(f -> f.data() != null && f.data().contains("\"itemType\":\"" + itemType + "\""))
                    .count();
            if (n >= minCount) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("SSE 未收到 ≥" + minCount + " 个 itemType=" + itemType
                + " frames=" + sse.frames());
    }

    private static void waitUntilToolCall(ConfigurableApplicationContext ctx, String threadId, long timeoutMs)
            throws InterruptedException {
        ItemRepository items = ctx.getBean(ItemRepository.class);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<RuntimeItemDO> calls = items.listByThread(threadId).stream()
                    .filter(i -> ItemType.TOOL_CALL.wire().equals(i.getType()))
                    .filter(i -> i.getContentJson() != null && i.getContentJson().contains("erp.slow"))
                    .toList();
            if (!calls.isEmpty()) {
                return;
            }
            // 若尚未写出 erp.slow，任意新 tool_call 后短暂等待亦可
            long any = items.listByThread(threadId).stream()
                    .filter(i -> ItemType.TOOL_CALL.wire().equals(i.getType()))
                    .count();
            if (any >= 4) {
                // 第 4 次 tool_call 即 slow 轮
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("超时未看到 erp.slow tool_call");
    }

    private HttpResponse<String> get(String url, String token, String workspaceId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Workspace-Id", workspaceId)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String url, String token, String workspaceId, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Workspace-Id", workspaceId)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
