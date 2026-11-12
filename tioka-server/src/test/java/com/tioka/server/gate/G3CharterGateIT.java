package com.tioka.server.gate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.policy.PolicyAdminApi;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.charter.CharterLifecycleService;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.orchestration.api.engine.DecisionCode;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.OrchRuleAppService;
import com.tioka.orchestration.infrastructure.mapper.OrchDecisionMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionDO;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.UserAdminService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 G3 闸门（M3-05 第 10 节）：授权书收紧 / 四眼 / DRYRUN / 负责人失效。
 * 双实例、Keycloak、开启鉴权（GateCluster.start(2, script, props, true)）。
 *
 * <p>契约映射：授权书动作类别 {@code ar.writeoff}（toolPatterns 覆盖 erp.update /
 * erp.deleteCustomer / erp.slow），单次 5 万（50_000_000_000 micros，换算参照 ADR-032
 * L92 与 CharterCompilerTest），scope={customerLevel:[C,D]}，禁区 {@code erp.deleteCustomer}
 * （MCP 桩等价映射 {@code biz.erp.deleteCustomer}）。金额 / customerLevel 经
 * resourceAttrs 直传（PolicyEvalPipelineStep.resourceAttrs：tool args 原始键 →
 * resource.attrs.*，amountMicros 数值型参与 escalate-amount 比较）。
 *
 * <p>每场景独立 GateCluster（LLM 剧本按场景静态选择，避免单剧本跨场景计数串扰）。
 * 负责人失效事件经进程内 Spring 监听（afterCommit）驱动——生产 MQ 主口径
 * {@code IdentityCharterConsumer} 与其进入同一状态机，幂等由状态转移 CAS 保证（登记偏离）。
 */
@Tag("gate")
class G3CharterGateIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long LIMIT_50K_MICROS = 50_000_000_000L;
    private static final long AMOUNT_30K_MICROS = 30_000_000_000L;
    private static final long AMOUNT_80K_MICROS = 80_000_000_000L;

    /** 授权书播种 + 双实例 + Keycloak（每场景独立 cluster）。 */
    private static final class Scene implements AutoCloseable {
        final GateCluster cluster;
        final TestFixtures.Actor admin;
        final TestFixtures.Actor owner;
        final String agentId;
        final String agentVersionId;
        final String workspaceId;

        Scene(String llmScript) {
            // 双实例、Keycloak、开启鉴权 + 授权书特性（M3-05 §10 环境契约）
            cluster = GateCluster.start(2, llmScript, Map.of(
                    "tioka.trust.charter.enabled", "true"), true);
            ConfigurableApplicationContext ctx = cluster.a();
            TestFixtures fixtures = ctx.getBean(TestFixtures.class);
            admin = fixtures.newActor("workspace_admin");
            owner = fixtures.newLimitedUser(admin, "member");
            workspaceId = admin.workspaceId();
            Seeded seeded = seed(ctx, admin.userId(), owner.userId(), workspaceId);
            agentId = seeded.agentId();
            agentVersionId = seeded.versionId();
        }

        ConfigurableApplicationContext ctx() {
            return cluster.a();
        }

        /** 以 owner 身份执行（Turn 发起人）。 */
        <T> T asOwner(Supplier<T> body) {
            return ctx().getBean(PrincipalHolder.class)
                    .runAs(principal(owner, "member"), body);
        }

        /** 以 workspace_admin（bootstrap 管理员）身份执行（审批人 / 管理操作）。 */
        <T> T asAdmin(Supplier<T> body) {
            return ctx().getBean(PrincipalHolder.class)
                    .runAs(principal(admin, "workspace_admin"), body);
        }

        void asOwner(Runnable body) {
            ctx().getBean(PrincipalHolder.class).runAs(principal(owner, "member"), body);
        }

        void asAdmin(Runnable body) {
            ctx().getBean(PrincipalHolder.class).runAs(principal(admin, "workspace_admin"), body);
        }

        RuntimeApi.TurnHandle startTurn(String threadId, String clientMsgId) {
            return asOwner(() -> ctx().getBean(RuntimeApi.class).startTurn(threadId,
                    new RuntimeApi.StartTurnCmd(clientMsgId,
                            List.of(RuntimeApi.InputItem.text("核销一笔")), null, "chat", null)));
        }

        /** 交接恢复后以新负责人（admin）身份发起：原 owner 已禁用，账号复核不通过。 */
        String newThreadAsAdmin() {
            return asAdmin(() -> ctx().getBean(RuntimeApi.class).createThread(
                    new RuntimeApi.CreateThreadCmd(agentVersionId, "chat", null, "chat", null)).threadId());
        }

        RuntimeApi.TurnHandle startTurnAsAdmin(String threadId, String clientMsgId) {
            return asAdmin(() -> ctx().getBean(RuntimeApi.class).startTurn(threadId,
                    new RuntimeApi.StartTurnCmd(clientMsgId,
                            List.of(RuntimeApi.InputItem.text("核销一笔")), null, "chat", null)));
        }

        RuntimeApi.TurnView awaitStatus(String turnId, long timeoutMs, TurnStatus wanted) {
            RuntimeApi runtime = ctx().getBean(RuntimeApi.class);
            long deadline = System.currentTimeMillis() + timeoutMs;
            RuntimeApi.TurnView last = null;
            while (System.currentTimeMillis() < deadline) {
                last = asOwner(() -> runtime.getTurn(turnId));
                if (last.status() == wanted) {
                    return last;
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            throw new AssertionError("turn 未在 " + timeoutMs + "ms 内到达 " + wanted + ": " + turnId
                    + " last=" + (last == null ? "null"
                    : last.status() + "/" + last.errorCode() + "/" + last.errorMessage()));
        }

        RuntimeApi.TurnView awaitTerminal(String turnId, long timeoutMs) {
            return awaitTerminalAs(turnId, timeoutMs, false);
        }

        /** 交接恢复后 thread 归属新负责人（admin），requireThread 的 owner 校验要求同身份查询。 */
        RuntimeApi.TurnView awaitTerminalAsAdmin(String turnId, long timeoutMs) {
            return awaitTerminalAs(turnId, timeoutMs, true);
        }

        private RuntimeApi.TurnView awaitTerminalAs(String turnId, long timeoutMs, boolean admin) {
            RuntimeApi runtime = ctx().getBean(RuntimeApi.class);
            PrincipalHolder holder = ctx().getBean(PrincipalHolder.class);
            Principal p = principal(admin ? this.admin : this.owner,
                    admin ? "workspace_admin" : "member");
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                RuntimeApi.TurnView v = holder.<RuntimeApi.TurnView>runAs(p, () -> runtime.getTurn(turnId));
                if (v.status().isTerminal()) {
                    return v;
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            throw new AssertionError("turn 未终态: " + turnId);
        }

        List<AuditEvent> audit(String action, String resourceType, String resourceId, AuditOutcome outcome) {
            return asAdmin(() -> ctx().getBean(AuditApi.class).query(new AuditApi.AuditQuery(
                    workspaceId, null, action, resourceType, resourceId, outcome,
                    null, null, null, 50)));
        }

        @Override public void close() {
            cluster.close();
        }
    }

    private record Seeded(String agentId, String versionId) {}

    private static Principal principal(TestFixtures.Actor actor, String role) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of(role), Set.of("*"), "password");
    }

    /**
     * 播种已发布授权书 Agent：owner 创建 Agent（owner_id 即负责人）；真实 saveDraft→publish
     * 路径（charter.enabled=true 使 CharterPublishCheck 生效，编译策略入 gov_policy）。
     */
    private static Seeded seed(ConfigurableApplicationContext ctx, String adminUserId,
                               String ownerUserId, String workspaceId) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        ModelAdminService models = ctx.getBean(ModelAdminService.class);
        AgentService agents = ctx.getBean(AgentService.class);
        McpAdminService mcpAdmin = ctx.getBean(McpAdminService.class);
        String tag = "g3chr-" + UUID.randomUUID().toString().substring(0, 8);

        Principal adminP = new Principal(adminUserId, workspaceId, PrincipalType.USER,
                Set.of("system_admin"), Set.of("*"), "password");
        Principal ownerP = new Principal(ownerUserId, workspaceId, PrincipalType.USER,
                Set.of("member"), Set.of("*"), "password");

        String planId = principals.runAs(adminP, () -> {
            var provider = models.createProvider(new CreateProviderRequest(tag + "-p",
                    ctx.getEnvironment().getProperty("tioka.gate.llm-base-url"), "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
            String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
            String plan = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
            models.publish(plan);
            return plan;
        });

        McpStore.McpServer server = principals.runAs(adminP, () -> {
            McpStore.McpServer s = mcpAdmin.register(tag + "-mcp",
                    ctx.getEnvironment().getProperty("tioka.gate.mcp-base-url"), "tok");
            mcpAdmin.refreshTools(s.id());
            mcpAdmin.updateToolRisk(s.id(), "erp.query", "READ");
            mcpAdmin.updateToolRisk(s.id(), "erp.update", "WRITE");
            mcpAdmin.updateToolRisk(s.id(), "erp.slow", "READ");
            mcpAdmin.updateToolRisk(s.id(), "erp.deleteCustomer", "WRITE");
            return s;
        });
        List<String> tools = List.of("erp.update", "erp.deleteCustomer", "erp.slow");

        return principals.runAs(ownerP, () -> {
            var agent = agents.create(tag + "-agent", workspaceId);
            ObjectNode charter = MAPPER.createObjectNode();
            charter.put("role", "应收核销专员");
            charter.put("departmentId", "finance");
            charter.put("mission", "应收核销");
            charter.put("actingAs", "DELEGATED");
            ArrayNode classes = charter.putArray("actionClasses");
            ObjectNode ac = classes.addObject();
            ac.put("code", "ar.writeoff");
            ac.put("name", "应收核销");
            ArrayNode patterns = ac.putArray("toolPatterns");
            tools.forEach(patterns::add);
            ObjectNode limits = ac.putObject("limits");
            limits.put("perCallAmountMicros", LIMIT_50K_MICROS);
            ObjectNode scope = limits.putObject("scope");
            ArrayNode levels = scope.putArray("customerLevel");
            levels.add("C");
            levels.add("D");
            ac.put("fourEyes", true);
            ac.put("ceiling", "L2");
            ac.put("delegable", false);
            charter.putArray("forbiddenTools").add("erp.deleteCustomer");
            charter.putObject("escalation").put("type", "OWNER");
            charter.putArray("kpis");
            charter.put("reviewEvery", "P90D");

            AgentSpec spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(
                            new AgentSpec.Instructions.Section("main", "you call mcp erp tools"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    new AgentSpec.ToolSet(List.of(),
                            List.of(new AgentSpec.ToolSet.McpBinding(server.id(), tools)),
                            List.of(), List.of()),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    // 写工具放行由授权书策略收紧（M3-05：策略只能收紧，不能放行）
                    new AgentSpec.ApprovalSettings(Map.of("erp.update", "auto",
                            "erp.deleteCustomer", "auto", "erp.slow", "auto"), null),
                    AgentSpec.Limits.defaults(),
                    charter, null, null, null, null, null, null, null);
            AgentService.DraftView draft = agents.saveDraft(agent.id(), spec,
                    agents.getDraft(agent.id()).revision());
            AgentService.PublishResult pub = agents.publish(agent.id(), draft.revision(), "g3-charter");
            return new Seeded(agent.id(), pub.versionId());
        });
    }

    private static String newThread(Scene scene) {
        return scene.asOwner(() -> scene.ctx().getBean(RuntimeApi.class).createThread(
                new RuntimeApi.CreateThreadCmd(scene.agentVersionId, "chat", null, "chat", null)).threadId());
    }

    private static String pendingApprovalId(ConfigurableApplicationContext ctx, String turnId) {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> ids = jdbc.queryForList(
                    "SELECT id FROM runtime_approval WHERE subject_id = ? AND status = 'PENDING'",
                    String.class, turnId);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("无 PENDING 审批: " + turnId);
    }

    private static JsonNode cardItem(ConfigurableApplicationContext ctx, String threadId) throws Exception {
        // ItemType.CARD.wire() = "card"（小写），与 runtime_item.type 存储值一致
        List<Map<String, Object>> rows = ctx.getBean(JdbcTemplate.class).queryForList(
                "SELECT content_json FROM runtime_item WHERE thread_id = ? AND type = 'card'",
                threadId);
        assertFalse(rows.isEmpty(), "应有审批卡片 item");
        return MAPPER.readTree(String.valueOf(rows.get(0).get("content_json")));
    }

    // ---------- 场景 ----------

    /** 场景 1+2：单次 3 万 ≤ 5 万 → 自动执行（等级 L2、可逆），MCP 桩真执行。 */
    @Test
    void withinLimitAutoExecutes() {
        try (Scene scene = new Scene("charter-auto")) {
            String threadId = newThread(scene);
            RuntimeApi.TurnHandle handle = scene.startTurn(threadId, "cm-g3-auto");
            RuntimeApi.TurnView done = scene.awaitTerminal(handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

            assertTrue(scene.cluster.mcp().calls().stream().anyMatch(c ->
                            "erp.update".equals(c.name())
                                    && ((Number) c.arguments().get("amountMicros")).longValue()
                                    == AMOUNT_30K_MICROS),
                    "额度内写工具应真执行（amountMicros=3 万）");
            assertTrue(scene.cluster.mcp().calls().stream()
                    .noneMatch(c -> "erp.deleteCustomer".equals(c.name())));
        }
    }

    /** 场景 3：单次 8 万 > 5 万 → ESCALATE 转审批；审批卡片带变更单；非发起人批准后 COMPLETED。 */
    @Test
    void overLimitEscalatesToApproval() throws Exception {
        try (Scene scene = new Scene("charter-escalate")) {
            String threadId = newThread(scene);
            RuntimeApi.TurnHandle handle = scene.startTurn(threadId, "cm-g3-esc");
            scene.awaitStatus(handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);

            ConfigurableApplicationContext ctx = scene.ctx();
            String approvalId = pendingApprovalId(ctx, handle.turnId());
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            Map<String, Object> approval = jdbc.queryForMap(
                    "SELECT status, policy, assignee_json FROM runtime_approval WHERE id = ?", approvalId);
            assertEquals("PENDING", approval.get("status"));
            assertTrue(String.valueOf(approval.get("assignee_json")).contains("fourEyes"),
                    "四眼类别 ESCALATE → assignee 标记 fourEyes");

            // 审批卡片：toolName / args / fourEyes（变更单 trust_action 关联为 M2-B6c
            // 已登记偏离——approval → commit 续跑未接，本批经 classify 挂起，无变更单）
            JsonNode card = cardItem(ctx, threadId);
            assertEquals("erp.update", card.path("toolName").asText());
            assertEquals(AMOUNT_80K_MICROS, card.path("args").path("amountMicros").asLong());
            assertTrue(card.path("fourEyes").asBoolean(false), "卡片带 fourEyes");

            // 非发起人（workspace_admin）批准 → 续跑 COMPLETED
            scene.asAdmin(() -> ctx.getBean(RuntimeApi.class).decideApproval(approvalId,
                    new RuntimeApi.ApprovalDecision(true, null, "ok")));
            RuntimeApi.TurnView done = scene.awaitTerminal(handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
            assertTrue(scene.cluster.mcp().calls().stream().anyMatch(c ->
                    "erp.update".equals(c.name())), "批准后 erp.update 真执行");
        }
    }

    /** 场景 4：客户等级 A 不在 scope {C,D} → 拒绝，审计记策略 @id charter:{agv}:ar.writeoff:scope。 */
    @Test
    void outOfScopeDeniedWithPolicyId() {
        try (Scene scene = new Scene("charter-deny-scope")) {
            String threadId = newThread(scene);
            RuntimeApi.TurnHandle handle = scene.startTurn(threadId, "cm-g3-scope");
            RuntimeApi.TurnView done = scene.awaitTerminal(handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(),
                    "拒绝结果回喂模型（Turn 不 FAIL），工具未执行");

            // 工具未真执行
            assertTrue(scene.cluster.mcp().calls().stream()
                    .noneMatch(c -> "erp.update".equals(c.name())), "scope 外调用不得触达 MCP");

            // 审计 DENIED 记录策略 @id
            List<AuditEvent> denied = scene.audit("tool.invoke", "tool", "erp.update", AuditOutcome.DENIED);
            assertFalse(denied.isEmpty(), "审计应有 tool.invoke DENIED");
            assertTrue(denied.get(0).reason().contains("charter:" + scene.agentVersionId + ":ar.writeoff:scope"),
                    () -> "审计 reason 应含 scope 策略 @id: " + denied.get(0).reason());
        }
    }

    /** 场景 5：禁区工具 erp.deleteCustomer（映射 biz.erp.deleteCustomer）→ 拒绝。 */
    @Test
    void forbiddenToolDenied() {
        try (Scene scene = new Scene("charter-forbidden")) {
            String threadId = newThread(scene);
            RuntimeApi.TurnHandle handle = scene.startTurn(threadId, "cm-g3-forbid");
            scene.awaitTerminal(handle.turnId(), 60_000);

            assertTrue(scene.cluster.mcp().calls().stream()
                    .noneMatch(c -> "erp.deleteCustomer".equals(c.name())), "禁区工具不得触达 MCP");
            List<AuditEvent> denied = scene.audit("tool.invoke", "tool", "erp.deleteCustomer",
                    AuditOutcome.DENIED);
            assertFalse(denied.isEmpty(), "审计应有 tool.invoke DENIED");
            assertTrue(denied.get(0).reason().contains(":forbidden-tool"),
                    () -> "禁区策略 @id: " + denied.get(0).reason());
        }
    }

    /** 场景 6：四眼——发起人 decideApproval → FORBIDDEN + 审计 FOUR_EYES_SELF_APPROVAL。 */
    @Test
    void fourEyesBlocksSelfApproval() {
        try (Scene scene = new Scene("charter-escalate")) {
            String threadId = newThread(scene);
            RuntimeApi.TurnHandle handle = scene.startTurn(threadId, "cm-g3-4eyes");
            scene.awaitStatus(handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            String approvalId = pendingApprovalId(scene.ctx(), handle.turnId());

            // 发起人（owner，非审批人）提交 → FORBIDDEN + 审计
            BizException ex = assertThrows(BizException.class, () ->
                    scene.asOwner(() -> scene.ctx().getBean(RuntimeApi.class).decideApproval(approvalId,
                            new RuntimeApi.ApprovalDecision(true, null, "self"))));
            assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
            List<AuditEvent> violation = scene.audit("approval.decide", "runtime_approval",
                    approvalId, AuditOutcome.DENIED);
            assertFalse(violation.isEmpty(), "四眼违规应有审计");
            assertEquals("FOUR_EYES_SELF_APPROVAL", violation.get(0).reason());

            // 非发起人批准成功
            scene.asAdmin(() -> scene.ctx().getBean(RuntimeApi.class).decideApproval(approvalId,
                    new RuntimeApi.ApprovalDecision(true, null, "ok")));
            RuntimeApi.TurnView done = scene.awaitTerminal(handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
        }
    }

    /** 场景 7：DRYRUN 策略命中 → 调用照常执行 + 审计 tool.policy_dryrun（本应拒绝）。 */
    @Test
    void dryrunPolicyAuditsWouldDenyButExecutes() {
        try (Scene scene = new Scene("charter-dryrun")) {
            // 播种前导入 workspace 级 DRYRUN 策略（cache 冷加载即含 DRYRUN，避免 60s TTL 兜底延迟）
            String cedar = """
                    @id("gate:dryrun:erp.update")
                    @effect("DENY")
                    forbid(principal, action in Tioka::Action::"erp.update", resource);
                    """;
            scene.asAdmin(() -> scene.ctx().getBean("policyAdminApi", PolicyAdminApi.class).importPolicy(
                    new PolicyAdminApi.ImportCommand(scene.workspaceId, cedar, PolicyMode.DRYRUN, "gate")));

            String threadId = newThread(scene);
            RuntimeApi.TurnHandle handle = scene.startTurn(threadId, "cm-g3-dryrun");
            RuntimeApi.TurnView done = scene.awaitTerminal(handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
            assertTrue(scene.cluster.mcp().calls().stream().anyMatch(c ->
                    "erp.update".equals(c.name())), "DRYRUN 命中照常执行");

            List<AuditEvent> dryrun = scene.audit("tool.policy_dryrun", "tool", "erp.update",
                    AuditOutcome.DENIED);
            assertFalse(dryrun.isEmpty(), "审计应有 tool.policy_dryrun");
            assertEquals("dryrun_would_deny", dryrun.get(0).reason());
            // 既有审计查询层缺陷（登记偏离，归 OBS 后续批次）：AuditApi.query 用 MyBatis
            // HashMap 接 PostgreSQL 结果，未加引号的驼峰别名 detailsJson / workspaceId 被
            // PG 折叠为小写，AuditService.toEvent 按驼峰取键失败 → 回读 details/workspaceId
            // 为空（DB 列实际有值）。此处按事件 id 直查原始列，验证策略 @id 确实落库。
            String detailsJson = scene.ctx().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT details_json FROM audit_event WHERE id = ?",
                    String.class, dryrun.get(0).id());
            assertTrue(detailsJson != null && detailsJson.contains("policyIds"),
                    () -> "details_json 应含 policyIds，实际=" + detailsJson);
            assertTrue(detailsJson != null && detailsJson.contains("gate:dryrun:erp.update"),
                    () -> "details_json 应含 DRYRUN 策略 @id，实际=" + detailsJson);
        }
    }

    /** 场景 8：负责人禁用 → SUSPENDED + 新 Turn 42301 + 编排 UNAVAILABLE；交接恢复后可服务。 */
    @Test
    void ownerDisableSuspendsAndOrchestrationUnavailable() {
        try (Scene scene = new Scene("three-tools")) {
            String threadId = newThread(scene);

            // 禁用负责人（管理员身份）→ SUSPENDED + trust_charter_event
            scene.asAdmin(() -> scene.ctx().getBean(UserAdminService.class)
                    .disable(scene.owner.userId()));
            JdbcTemplate jdbc = scene.ctx().getBean(JdbcTemplate.class);
            assertEquals("SUSPENDED", jdbc.queryForObject(
                    "SELECT charter_status FROM agent_agent WHERE id = ?", String.class, scene.agentId));
            Integer events = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trust_charter_event WHERE agent_id = ? AND kind = 'SUSPEND'",
                    Integer.class, scene.agentId);
            assertEquals(1, events, "一条 SUSPEND 事件");

            // 新 Turn → 42301
            BizException ex = assertThrows(BizException.class,
                    () -> scene.startTurn(threadId, "cm-g3-suspended"));
            assertEquals(ErrorCode.AGENT_SUSPENDED, ex.getErrorCode(), "42301");

            // 编排决定 UNAVAILABLE（派工复验：授权书非 ACTIVE）
            String eventId = ingestDemoEvent(scene);
            OrchDecisionDO decision = latestDecision(scene, eventId);
            assertEquals(DecisionCode.UNAVAILABLE.name(), decision.getDecision());
            assertEquals("charter_suspended", decision.getReason());

            // 交接恢复：assignOwner → HANDOVER → acceptCharter → ACTIVE → 新 Turn 成功
            scene.asAdmin(() -> scene.ctx().getBean(CharterLifecycleService.class)
                    .assignOwner(scene.workspaceId, scene.agentId, scene.admin.userId()));
            scene.asAdmin(() -> scene.ctx().getBean(CharterLifecycleService.class)
                    .acceptCharter(scene.workspaceId, scene.agentId));
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT charter_status FROM agent_agent WHERE id = ?", String.class, scene.agentId));

            String threadId2 = scene.newThreadAsAdmin();
            RuntimeApi.TurnHandle handle = scene.startTurnAsAdmin(threadId2, "cm-g3-restored");
            RuntimeApi.TurnView done = scene.awaitTerminalAsAdmin(handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
        }
    }

    /** 场景 8 附：交接（HANDOVER）期间编排同样 UNAVAILABLE；恢复后派工落 agent_version_id。 */
    @Test
    void handoverStateAlsoUnavailableAndMandateCarriesAgentVersion() {
        try (Scene scene = new Scene("three-tools")) {
            scene.asAdmin(() -> scene.ctx().getBean(CharterLifecycleService.class)
                    .assignOwner(scene.workspaceId, scene.agentId, scene.admin.userId()));
            String eventId = ingestDemoEvent(scene);
            OrchDecisionDO decision = latestDecision(scene, eventId);
            assertEquals(DecisionCode.UNAVAILABLE.name(), decision.getDecision());
            assertEquals("charter_suspended", decision.getReason(), "HANDOVER 亦非 ACTIVE");

            // 恢复后派工 ACCEPT，mandate 记 agent_version_id（V201 / ADR-029 第 36 条）
            scene.asAdmin(() -> scene.ctx().getBean(CharterLifecycleService.class)
                    .acceptCharter(scene.workspaceId, scene.agentId));
            String eventId2 = ingestDemoEvent(scene);
            OrchDecisionDO accept = latestDecision(scene, eventId2);
            assertEquals(DecisionCode.ACCEPT.name(), accept.getDecision());
            Integer mandate = scene.ctx().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM orch_mandate WHERE rule_version_id = ? AND agent_version_id = ?",
                    Integer.class, accept.getRuleVersionId(), scene.agentVersionId);
            assertEquals(1, mandate, "orch_mandate.agent_version_id 落 V201 列");
        }
    }

    /** 编排播种 + ingest：unique eventType + conditionCel attrs.channel=='feishu' + agent 动作。 */
    private static String ingestDemoEvent(Scene scene) {
        String ruleId = scene.asAdmin(() -> scene.ctx().getBean(OrchRuleAppService.class)
                .createRule("g3-charter-" + UUID.randomUUID().toString().substring(0, 8)).id());
        String eventType = "demo." + UUID.randomUUID().toString().substring(0, 8);
        String versionId = scene.asAdmin(() -> scene.ctx().getBean(OrchRuleAppService.class)
                .createVersion(ruleId, new OrchRuleAppService.CreateVersionCommand(
                        eventType,
                        "attrs.channel == 'feishu'",
                        List.of(Map.of("type", "agent", "agentVersionId", scene.agentVersionId,
                                "prompt", "处理 {{event.body}}")),
                        "parallel", "auto", null, null, null, null,
                        scene.admin.userId())).id());
        scene.asAdmin(() -> scene.ctx().getBean(OrchRuleAppService.class)
                .publish(ruleId, versionId, new OrchRuleAppService.PublishCommand("STABLE", null, null)));
        OrchApi.EventAck ack = scene.asAdmin(() -> scene.ctx().getBean(OrchApi.class).ingest(
                new OrchApi.EventEnvelope("feishu", eventType, "idem-" + UUID.randomUUID(),
                        "subj-" + UUID.randomUUID().toString().substring(0, 8),
                        Map.of("channel", "feishu"), Map.of("text", "核销"), 0)));
        return ack.eventId();
    }

    private static OrchDecisionDO latestDecision(Scene scene, String eventId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<OrchDecisionDO> rows = scene.ctx().getBean(OrchDecisionMapper.class).selectList(
                    Wrappers.<OrchDecisionDO>lambdaQuery()
                            .eq(OrchDecisionDO::getEventId, eventId)
                            .orderByDesc(OrchDecisionDO::getCreateAt));
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("无 orch_decision: " + eventId);
    }
}
