package com.tioka.server.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
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
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.gate.support.KeycloakLogin;
import com.tioka.server.gate.support.LlmStubServer;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.WorkspaceMemberService;
import com.tioka.system.application.auth.PrincipalLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸门 G3：委派链全链路（M3-01「闸门 G3 追加用例」L235，批次 C-17）。
 *
 * <p>权威断言（docs/architecture/construction/M3-01-子Agent与委派授予.md）：
 * 双实例，Keycloak 用户 A（区域华东）在对话里调主管 Agent；主管委派规则判定 Agent。断言：
 * ①子 Agent 调用主管有、自己没有的写工具被拒并记录两节委派链；
 * ②主管禁区内的工具在子 Agent 处也被拒；
 * ③子任务结束后其授予失效；
 * ④把 A 移出空间后 60 秒内整条链的工具调用被拒。
 *
 * <p>实现口径（MAS-01〜03 生产设施，无 mock）：
 * <ul>
 *   <li>①②走完整 Turn 链：主管 LLM 桩决定调 {@code agent.delegate}（narrow 只留 erp.query），
 *       子 Turn 桩强制调 erp.update（父有子无）与 erp.slow（父授予本就没有）→
 *       2.5 步 {@code DelegationGrantCheck} 双双 GRANT_DENIED；</li>
 *   <li>两节委派链断言：rt_grant.chain_json/depth=2/parent_id + 子授予 tools={erp.query}；
 *       审计断言 tool.invoke DENIED（audit_event 委派链五列 V221 已建但写入未接线，
 *       委派链/授予 ID 以 rt_grant + runtime_thread.grant_id 承载，登记偏离）；</li>
 *   <li>③子 Turn 终态 → {@code SubAgentReaper} 级联 reap（REAPED）+ 父授予回拨；</li>
 *   <li>④经 {@code WorkspaceMemberService.remove} 移出空间（MemberRemovedEvent → 缓存失效），
 *       60s 内父授予工具调用在第 4 步权限重载抛 WORKSPACE_FORBIDDEN，子授予调用 GRANT_INACTIVE。</li>
 * </ul>
 */
@Tag("gate")
class G3DelegationGateIT {
    private static final String WS = "ws_sales";
    private static final String SCRIPT = "g3-delegation";

    @Test
    void delegationChainDenialReapAndMemberRemoval() throws Exception {
        try (GateCluster cluster = GateCluster.start(2, SCRIPT,
                Map.of("tioka.runtime.delegate.enabled", true,
                        "tioka.trust.charter.enabled", true), true)) {
            ensureWsSales(cluster.a());
            // —— 1. Keycloak 用户 A（区域华东）登录 → OIDC 自动开通进 ws_sales ——
            cluster.keycloak().ensureRegionUsers();
            String token = new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0))
                    .login(KeycloakGateSupport.USER_EAST, KeycloakGateSupport.PASSWORD_EAST);
            assertTrue(token.startsWith("tka_"), token);

            JdbcTemplate jdbc = cluster.a().getBean(JdbcTemplate.class);
            Long uid = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, KeycloakGateSupport.USER_EAST);
            assertNotNull(uid);
            // 播种 MCP/模型/Agent 发布需 workspace_admin（member 缺 model:read / agent:write，
            // RefPermissionPublishCheck 会以 REF_FORBIDDEN 拒绝发布）
            jdbc.update("UPDATE sys_member SET role_code = 'workspace_admin' WHERE user_id = ? AND workspace_id = ?",
                    String.valueOf(uid), WS);
            PrincipalLoader loader = cluster.a().getBean(PrincipalLoader.class);
            Principal east = loader.load(uid, WS);
            PrincipalHolder principals = cluster.a().getBean(PrincipalHolder.class);
            ObjectMapper mapper = cluster.a().getBean(ObjectMapper.class);

            // —— 2. 播种 MCP 桩（erp.query/update/slow）+ 模型 + 子/父 Agent（租户行需 Principal 上下文）——
            Seeded seeded = principals.runAs(east, () -> {
                McpAdminService mcpAdmin = cluster.a().getBean(McpAdminService.class);
                String tag = Ids.newId("g3dlg");
                McpStore.McpServer s = mcpAdmin.register(tag + "-mcp", cluster.mcp().baseUrl(), "tok");
                mcpAdmin.refreshTools(s.id());
                mcpAdmin.updateToolRisk(s.id(), "erp.query", "READ");
                mcpAdmin.updateToolRisk(s.id(), "erp.update", "WRITE");
                mcpAdmin.updateToolRisk(s.id(), "erp.slow", "READ");

                ModelAdminService models = cluster.a().getBean(ModelAdminService.class);
                var provider = models.createProvider(new CreateProviderRequest(tag + "-llm", cluster.llm().baseUrl(), "sk-g3"));
                String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
                String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-stub"));
                String plan = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
                models.publish(plan);

                AgentService agents = cluster.a().getBean(AgentService.class);
                // 子 Agent：绑定 erp.query/update/slow（含主管禁区工具），无授权书；
                // erp.update 审批策略 auto（WRITE 默认需人工审批且审批闸先于授予检查——
                // 若挂起则子 Turn 永远 WAITING_APPROVAL；auto 放行后由授予检查 GRANT_DENIED 拒绝，契约①）
                AgentStore.Agent child = agents.create(tag + "-child", WS);
                AgentSpec childSpec = new AgentSpec(1,
                        new AgentSpec.Profile(child.name(), null, null, List.of(), "CHAT"),
                        new AgentSpec.Instructions(List.of(
                                new AgentSpec.Instructions.Section("main", "子Agent：审查合同"))),
                        new AgentSpec.ModelPolicy(plan, false),
                        new AgentSpec.ToolSet(List.of(),
                                List.of(new AgentSpec.ToolSet.McpBinding(s.id(),
                                        List.of("erp.query", "erp.update", "erp.slow"))),
                                List.of(), List.of()),
                        List.of(),
                        AgentSpec.KnowledgeBinding.empty(),
                        AgentSpec.SandboxPolicy.defaults(),
                        new AgentSpec.ApprovalSettings(Map.of("erp.update", "auto"), null),
                        AgentSpec.Limits.defaults(),
                        null, null, null, null, null, null, null, null);
                AgentService.PublishResult childPublished = agents.publishSpec(child.id(), childSpec);

                // 主管 Agent：授权书（ERP_WRITE 可下放）+ subAgents.allowed=[子 Agent]；工具集不含 erp.slow（主管禁区）
                AgentStore.Agent parent = agents.create(tag + "-parent", WS);
                JsonNode charter;
                JsonNode subAgents;
                try {
                    charter = mapper.readTree("""
                            {"role":"erp-supervisor","mission":"主管：委派子Agent执行只读审查",
                             "actionClasses":[{"code":"ERP_WRITE","name":"ERP写操作","toolPatterns":["erp.*"],"delegable":true}],
                             "delegable":["ERP_WRITE"],"forbiddenTools":["erp.slow"]}""");
                    subAgents = mapper.readTree("{\"allowed\":[\"" + child.id() + "\"]}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                AgentSpec parentSpec = new AgentSpec(1,
                        new AgentSpec.Profile(parent.name(), null, null, List.of(), "CHAT"),
                        new AgentSpec.Instructions(List.of(
                                new AgentSpec.Instructions.Section("main", "主管：需要时委派子Agent"))),
                        new AgentSpec.ModelPolicy(plan, false),
                        new AgentSpec.ToolSet(List.of(),
                                List.of(new AgentSpec.ToolSet.McpBinding(s.id(),
                                        List.of("erp.query", "erp.update"))),
                                List.of(), List.of()),
                        List.of(),
                        AgentSpec.KnowledgeBinding.empty(),
                        AgentSpec.SandboxPolicy.defaults(),
                        AgentSpec.ApprovalSettings.empty(),
                        AgentSpec.Limits.defaults(),
                        charter, null, null, null, null, null, null, null);
                // SchemaPublishCheck 仍冻结 subAgents（M1 预留字段必须为 null），而委派运行时
                // DelegateToolProvider.allowedByParent 消费 subAgents.allowed —— 设施缺口：
                // 先按 subAgents=null 合法发布（走完整 charter 编译），再回填 agent_version.spec_json
                // 的 subAgents 节点（偏差登记：MAS-01 发布闸未解冻 subAgents）。
                AgentService.PublishResult parentPublished = agents.publishSpec(parent.id(), parentSpec);
                try {
                    ObjectNode parentJson = (ObjectNode) mapper.valueToTree(parentSpec);
                    parentJson.set("subAgents", subAgents);
                    jdbc.update("UPDATE agent_version SET spec_json = ? WHERE id = ?",
                            mapper.writeValueAsString(parentJson), parentPublished.versionId());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return new Seeded(s.id(), plan, child.id(),
                        childPublished.versionId(), parentPublished.versionId());
            });
            String planId = seeded.planId();

            // —— 3. LLM 桩剧本：主管委派 → 子越权双拒 → 子收尾 → 父收尾 ——
            LlmStubServer.Script script = new LlmStubServer.Script();
            script.loop = false;
            script.calls = List.of(
                    delegateCall(seeded.childAgentId()),
                    deniedToolCalls(),
                    textCall("子任务完成：合同 HT-1 审查通过"),
                    textCall("主管收到子Agent结论，流程结束"));
            cluster.llm().putScript(SCRIPT, script);

            // —— 4. A 经 RuntimeApi 发起对话轮（主管决定调 agent.delegate，真实委派链）——
            RuntimeApi runtime = cluster.a().getBean(RuntimeApi.class);
            RuntimeApi.ThreadView thread = principals.runAs(east, () ->
                    runtime.createThread(new RuntimeApi.CreateThreadCmd(
                            seeded.parentVersionId(), "chat", null, "g3-delegation", null)));
            RuntimeApi.TurnHandle handle = principals.runAs(east, () ->
                    runtime.startTurn(thread.threadId(), new RuntimeApi.StartTurnCmd(
                            "cm-g3-dlg-1",
                            List.of(RuntimeApi.InputItem.text("请委派子Agent审查合同 HT-1")),
                            null, "chat", null)));

            GateSeed.Bundle bundle = new GateSeed.Bundle(
                    String.valueOf(uid), WS, token, seeded.parentVersionId(), planId);
            RuntimeApi.TurnView parentTurn;
            try {
                parentTurn = GateSeed.awaitTerminal(cluster.b(), bundle,
                        handle.turnId(), 120_000);
            } catch (AssertionError e) {
                // 诊断：dump 父/子 thread items + turn 状态 + 授予行
                List<Map<String, Object>> itemsDump = jdbc.queryForList(
                        "SELECT thread_id, type, seq, substring(content_json, 1, 400) AS content "
                                + "FROM runtime_item WHERE thread_id IN "
                                + "(SELECT id FROM runtime_thread WHERE id = ? OR parent_turn_id = ?) "
                                + "ORDER BY thread_id, seq", thread.threadId(), handle.turnId());
                itemsDump.forEach(i -> System.out.println("[g3-diag] item " + i));
                List<Map<String, Object>> turnsDump = jdbc.queryForList(
                        "SELECT id, thread_id, status FROM runtime_turn "
                                + "WHERE thread_id IN (SELECT id FROM runtime_thread WHERE id = ? OR parent_turn_id = ?)",
                        thread.threadId(), handle.turnId());
                turnsDump.forEach(i -> System.out.println("[g3-diag] turn " + i));
                List<Map<String, Object>> grantDump = jdbc.queryForList(
                        "SELECT id, parent_id, depth, status FROM rt_grant");
                grantDump.forEach(i -> System.out.println("[g3-diag] grant " + i));
                throw e;
            }
            assertEquals(TurnStatus.COMPLETED, parentTurn.status(),
                    () -> parentTurn.errorMessage());

            // —— 5. 子 thread / 两节授予链 ——
            String parentGrantId = jdbc.queryForObject(
                    "SELECT grant_id FROM runtime_thread WHERE id = ?", String.class, thread.threadId());
            assertNotNull(parentGrantId, "主管 thread 应有根授予");
            Map<String, Object> childRow = jdbc.queryForMap(
                    "SELECT id, grant_id FROM runtime_thread WHERE parent_turn_id = ?", handle.turnId());
            String childThreadId = String.valueOf(childRow.get("id"));
            String childGrantId = String.valueOf(childRow.get("grant_id"));
            assertNotNull(childGrantId, "子 thread 应携带委派授予 ID");

            GrantService grants = cluster.a().getBean(GrantService.class);
            DelegationGrant childGrant = grants.get(childGrantId);
            assertNotNull(childGrant);

            // ① 授予收窄 + 两节委派链（rt_grant 链 + 子授予工具集）
            assertEquals(parentGrantId, childGrant.parentGrantId(), "子授予父指针应指向主管根授予");
            assertEquals(2, childGrant.chain().size(), "委派链应有两节（主管 + 子Agent）");
            assertEquals(Set.of("erp.query"), childGrant.tools(), "子授予应收窄到 narrow 工具集");
            Map<String, Object> grantRow = jdbc.queryForMap(
                    "SELECT parent_id, root_principal, chain_json, depth FROM rt_grant WHERE id = ?",
                    childGrantId);
            assertEquals(parentGrantId, grantRow.get("parent_id"), "rt_grant.parent_id 应指向根授予");
            assertEquals(2, ((Number) grantRow.get("depth")).intValue(), "rt_grant.depth 应为 2");
            assertEquals("user:" + uid, grantRow.get("root_principal"), "链根 run_as 应为用户 A");
            String chainJson = String.valueOf(grantRow.get("chain_json"));
            assertTrue(chainJson.contains(seeded.parentVersionId())
                            && chainJson.contains(seeded.childVersionId()),
                    "chain_json 应记录两节 agentVersionId: " + chainJson);
            // ① 审计：子链越权写工具 DENIED（子 Agent 调主管有、自己没有的写工具）
            AuditApi audit = cluster.a().getBean(AuditApi.class);
            assertFalse(audit.query(new AuditApi.AuditQuery(WS, String.valueOf(uid),
                            "tool.invoke", "tool", "erp.update", AuditOutcome.DENIED,
                            null, null, null, 20)).isEmpty(),
                    "审计应有子链 erp.update DENIED");

            // ② 主管禁区内的工具（erp.slow 不在主管授予）在子 Agent 处也被拒
            assertFalse(audit.query(new AuditApi.AuditQuery(WS, String.valueOf(uid),
                            "tool.invoke", "tool", "erp.slow", AuditOutcome.DENIED,
                            null, null, null, 20)).isEmpty(),
                    "审计应有子链 erp.slow（主管禁区）DENIED");
            List<com.tioka.runtime.infrastructure.persistence.RuntimeItemDO> childItems =
                    cluster.a().getBean(com.tioka.runtime.kernel.infrastructure.ItemRepository.class)
                            .listByThread(childThreadId);
            assertTrue(childItems.stream().anyMatch(i ->
                            i.getContentJson() != null && i.getContentJson().contains("erp.update")
                                    && i.getContentJson().contains("GRANT_DENIED")),
                    "子 thread 应有 erp.update GRANT_DENIED 工具结果");
            assertTrue(childItems.stream().anyMatch(i ->
                            i.getContentJson() != null && i.getContentJson().contains("erp.slow")
                                    && i.getContentJson().contains("不在委派授予内")),
                    "子 thread 应有 erp.slow GRANT_DENIED 工具结果");

            // ③ 子任务结束后其授予失效（SubAgentReaper 级联 reap），主管根授予仍有效
            assertEquals(GrantStatus.REAPED, childGrant.status(), "子任务终态后子授予应 REAPED");
            assertEquals(GrantStatus.ACTIVE, grants.get(parentGrantId).status(), "主管根授予不受子回收影响");
            assertEquals("REAPED", jdbc.queryForObject(
                    "SELECT status FROM rt_grant WHERE id = ?", String.class, childGrantId));

            // ④ 移出空间前：父授予链路工具调用可用（基线）
            // （真实 Turn 链路中 gateway.call 恒在 TurnRunner 的 runAs 上下文内执行；
            //   SPI 直调须显式补齐，否则 MCP provider 读 mcp_server 触发租户行拦截）
            ToolGateway gateway = cluster.a().getBean(ToolGateway.class);
            Map<String, Object> parentToolCfg = Map.of("mcp", List.of(
                    Map.of("serverId", seeded.mcpServerId(), "allowlist", List.of("erp.query", "erp.update"))));
            ToolProvider.ToolResult pre = principals.runAs(east, () -> gateway.call(
                    ToolCallContexts.forTurn(east, thread.threadId(), handle.turnId(),
                            seeded.parentVersionId(), "chat", List.of(), parentToolCfg, parentGrantId),
                    "erp.query", Map.of("q", "baseline")));
            assertTrue(pre.ok(), "移出空间前父链 erp.query 应可用: " + pre.content());

            // ④ 移出空间（WorkspaceMemberService 真实路径：member.remove 审计 + 缓存失效 + 事件）
            Long adminUid = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = 'admin'", Long.class);
            // east 被提升为 workspace_admin（播种需要）；移除唯一管理员会被「空间至少需要一名
            // 管理员」拒绝 → 先按真实管理员移交路径把 admin 加为空间管理员
            Integer adminMember = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_member WHERE workspace_id = ? AND user_id = ?",
                    Integer.class, WS, String.valueOf(adminUid));
            if (adminMember == null || adminMember == 0) {
                jdbc.update("INSERT INTO sys_member (workspace_id, user_id, role_code) "
                        + "VALUES (?, ?, 'workspace_admin')", WS, String.valueOf(adminUid));
            }
            Principal admin = loader.load(adminUid, WS);
            WorkspaceMemberService members = cluster.a().getBean(WorkspaceMemberService.class);
            principals.runAs(admin, () -> {
                members.remove(WS, String.valueOf(uid));
                return null;
            });
            assertFalse(audit.query(new AuditApi.AuditQuery(WS, String.valueOf(adminUid),
                            "member.remove", "workspace_member", WS + ":" + uid,
                            AuditOutcome.SUCCESS, null, null, null, 20)).isEmpty(),
                    "审计应有 member.remove");

            // ④ 60 秒内整条链的工具调用被拒：父授予调用在第 4 步权限重载抛 WORKSPACE_FORBIDDEN
            long deadline = System.currentTimeMillis() + 60_000L;
            BizException denied = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    principals.runAs(east, () -> gateway.call(
                            ToolCallContexts.forTurn(east, thread.threadId(), handle.turnId(),
                                    seeded.parentVersionId(), "chat", List.of(), parentToolCfg, parentGrantId),
                            "erp.query", Map.of("q", "after-remove")));
                    denied = null;
                } catch (BizException e) {
                    denied = e;
                }
                if (denied != null) {
                    break;
                }
                Thread.sleep(200);
            }
            assertNotNull(denied, "移出空间后 60s 内父链工具调用应被拒");
            assertEquals(ErrorCode.WORKSPACE_FORBIDDEN, denied.getErrorCode(),
                    "拒绝应来自权限重载（不是该空间成员）: " + denied.getMessage());
            // 子授予链同样被拒（2.5 步 GRANT_INACTIVE，授予已随子任务回收）
            Map<String, Object> childToolCfg = Map.of("mcp", List.of(
                    Map.of("serverId", seeded.mcpServerId(),
                            "allowlist", List.of("erp.query", "erp.update", "erp.slow"))));
            ToolProvider.ToolResult childCall = principals.runAs(east, () -> gateway.call(
                    ToolCallContexts.forTurn(east, childThreadId, handle.turnId(),
                            seeded.childVersionId(), "chat", List.of(), childToolCfg, childGrantId),
                    "erp.query", Map.of("q", "after-remove")));
            assertFalse(childCall.ok(), "移出空间后子链工具调用应被拒");
            assertTrue(childCall.content().contains("GRANT_INACTIVE"), childCall.content());
        }
    }

    /** 第 1 步：主管调 agent.delegate（narrow 只留 erp.query）。 */
    private static LlmStubServer.Call delegateCall(String childAgentId) {
        LlmStubServer.Call call = new LlmStubServer.Call();
        LlmStubServer.ToolCallSpec tc = new LlmStubServer.ToolCallSpec();
        tc.name = "agent.delegate";
        tc.arguments = Map.of(
                "agentId", childAgentId,
                "task", "审查合同 HT-1 的最近变更",
                "narrow", Map.of("tools", List.of("erp.query")));
        call.tool_calls = List.of(tc);
        return call;
    }

    /** 第 2 步：子 Turn 强制越权调用 erp.update（父有子无）+ erp.slow（主管禁区）。 */
    private static LlmStubServer.Call deniedToolCalls() {
        LlmStubServer.Call call = new LlmStubServer.Call();
        LlmStubServer.ToolCallSpec update = new LlmStubServer.ToolCallSpec();
        update.name = "erp.update";
        update.arguments = Map.of("q", "越权写");
        LlmStubServer.ToolCallSpec slow = new LlmStubServer.ToolCallSpec();
        slow.name = "erp.slow";
        slow.arguments = Map.of("q", "主管禁区");
        call.tool_calls = List.of(update, slow);
        return call;
    }

    private static LlmStubServer.Call textCall(String text) {
        LlmStubServer.Call call = new LlmStubServer.Call();
        call.content = text;
        return call;
    }

    private static void ensureWsSales(ConfigurableApplicationContext ctx) {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_workspace WHERE id = ?", Integer.class, WS);
        if (n != null && n == 0) {
            jdbc.update("""
                    INSERT INTO sys_workspace (id, name, owner_user_id, create_at, update_at, create_by, update_by, deleted)
                    VALUES ('ws_sales', 'Sales', 1, now(), now(), 'system', 'system', FALSE)
                    """);
        }
    }

    /** 播种产物。 */
    private record Seeded(String mcpServerId, String planId,
                          String childAgentId, String childVersionId,
                          String parentVersionId) {}
}
