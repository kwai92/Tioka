package com.tioka.server.tru;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.application.charter.CharterLifecycleService;
import com.tioka.governance.application.charter.IdentityCharterConsumer;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.application.UserAdminService;
import com.tioka.system.application.WorkspaceMemberService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TRU-03 验收（M3-05 §4 / ADR-032「负责人与生命周期」）：负责人失效自动暂停与交接。
 *
 * <ul>
 *   <li>禁用负责人 → agent_agent.charter_status=SUSPENDED + trust_charter_event 一条 SUSPEND；
 *       重复事件不新增 SUSPEND（幂等＝状态转移幂等，M3-05 §4「重复事件只处理一次」）</li>
 *   <li>新 thread / 新 Turn → AGENT_SUSPENDED(42301 → HTTP 423)</li>
 *   <li>运行中 Turn 在下一步开始前被取消（CANCELLED，error_code=charter_suspended）</li>
 *   <li>assignOwner → HANDOVER；acceptCharter → ACTIVE（reviewed_at 非空）→ 新 Turn 成功</li>
 *   <li>成员移出空间 → 该空间名下 Agent 暂停（经 WorkspaceMemberService.remove 真实入口，
 *       进程内监听路径）；同一事件经 MQ 消费者映射（IdentityCharterConsumer.dispatch）重复
 *       进入状态机亦幂等</li>
 * </ul>
 *
 * <p>装配参照 ApprovalFlowIT（DualInstance，dispatcher 开启）；授权书特性开启
 * {@code tioka.trust.charter.enabled=true}（CharterAutoConfiguration 装配状态机与消费者）。
 * MQ 全链路（RocketMQ Testcontainers）不在此覆盖：消费者为标准 IdempotentConsumer，
 * payload 契约经 {@code IdentityCharterConsumer.dispatch} 直调覆盖，登记偏离。
 */
@Tag("it")
class OwnerLifecycleIT {

    private static final Map<String, Object> CHARTER_ENABLED =
            Map.of("tioka.trust.charter.enabled", "true");

    @Test
    void disableOwnerSuspendsAndBlocksNewTurns() {
        try (DualInstance di = DualInstance.start("three-tools", CHARTER_ENABLED)) {
            ConfigurableApplicationContext ctx = di.a();
            TestFixtures fixtures = ctx.getBean(TestFixtures.class);
            TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
            TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "member");
            Seeded agent = seedAgent(ctx, owner.userId(), admin.userId(), admin.workspaceId(), List.of());

            String threadId = createThread(ctx, principal(owner), agent.versionId());

            // ① 禁用负责人 → SUSPENDED + 一条 SUSPEND 事件
            as(ctx, admin, "system_admin").run(() -> disable(ctx, owner.userId()));
            assertEquals("SUSPENDED", charterStatus(ctx, agent.agentId()));
            assertEquals(1, charterEventCount(ctx, agent.agentId(), "SUSPEND"));

            // ② 重复事件（MQ 重投 / 双路进入同一状态机）→ 幂等，不新增 SUSPEND
            ctx.getBean(CharterLifecycleService.class).suspendByOwnerDisable(owner.userId());
            assertEquals(1, charterEventCount(ctx, agent.agentId(), "SUSPEND"),
                    "重复事件只处理一次（状态转移幂等）");

            // ③ 新 Turn / 新 thread → AGENT_SUSPENDED(42301)
            RuntimeApi runtime = ctx.getBean(RuntimeApi.class);
            BizException turnEx = assertThrows(BizException.class, () ->
                    as(ctx, owner, "member").run(() -> runtime.startTurn(threadId,
                            new RuntimeApi.StartTurnCmd("cm-suspended-" + UUID.randomUUID(),
                                    List.of(RuntimeApi.InputItem.text("hi")), null, "chat", null))));
            assertEquals(ErrorCode.AGENT_SUSPENDED, turnEx.getErrorCode(), "42301");
            BizException threadEx = assertThrows(BizException.class, () ->
                    as(ctx, owner, "member").run(() -> runtime.createThread(
                            new RuntimeApi.CreateThreadCmd(agent.versionId(), "chat", null, "chat", null))));
            assertEquals(ErrorCode.AGENT_SUSPENDED, threadEx.getErrorCode(), "42301");
        }
    }

    @Test
    void runningTurnCancelledOnOwnerDisable() {
        // erp.slow（3s）保证 Turn 在禁用发生时仍在 RUNNING。
        // Turn 由 successor 发起：owner 禁用同时会经 PrincipalTurnRevoker 取消 owner 本人
        // 发起的 Turn（FND-04e，principal_revoked）；由他人发起才能精确验证 TRU-03 的
        // 授权书暂停取消（charter_suspended）。
        try (DualInstance di = DualInstance.start("mcp-slow-cancel", CHARTER_ENABLED)) {
            ConfigurableApplicationContext ctx = di.a();
            TestFixtures fixtures = ctx.getBean(TestFixtures.class);
            TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
            TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "member");
            TestFixtures.Actor successor = fixtures.newLimitedUser(admin, "member");
            Seeded agent = seedAgent(ctx, owner.userId(), admin.userId(), admin.workspaceId(), List.of("erp.slow"));

            String threadId = createThread(ctx, principal(successor), agent.versionId());
            RuntimeApi.TurnHandle handle = as(ctx, successor, "member").run(() -> startTurn(
                    ctx, threadId, "cm-cancel-owner"));

            // 等待进入 RUNNING（慢工具已开始执行）
            awaitStatus(ctx, principal(successor), handle.turnId(), 30_000, TurnStatus.RUNNING);

            as(ctx, admin, "system_admin").run(() -> disable(ctx, owner.userId()));

            RuntimeApi.TurnView view = awaitStatus(ctx, principal(successor), handle.turnId(),
                    30_000, TurnStatus.CANCELLED);
            assertEquals("charter_suspended", view.errorCode(),
                    "取消原因写入 runtime_turn.error_code（审计可读）");
        }
    }

    @Test
    void handoverRestoresService() {
        try (DualInstance di = DualInstance.start("three-tools", CHARTER_ENABLED)) {
            ConfigurableApplicationContext ctx = di.a();
            TestFixtures fixtures = ctx.getBean(TestFixtures.class);
            TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
            TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "member");
            TestFixtures.Actor successor = fixtures.newLimitedUser(admin, "member");
            Seeded agent = seedAgent(ctx, owner.userId(), admin.userId(), admin.workspaceId(), List.of());

            as(ctx, admin, "system_admin").run(() -> disable(ctx, owner.userId()));
            assertEquals("SUSPENDED", charterStatus(ctx, agent.agentId()));

            // 交接：空间管理员指定新负责人 → HANDOVER；新负责人确认 → ACTIVE + reviewed_at
            as(ctx, admin, "workspace_admin").run(() -> ctx.getBean(CharterLifecycleService.class)
                    .assignOwner(admin.workspaceId(), agent.agentId(), successor.userId()));
            assertEquals("HANDOVER", charterStatus(ctx, agent.agentId()));
            assertEquals(1, charterEventCount(ctx, agent.agentId(), "HANDOVER"));

            as(ctx, successor, "member").run(() -> ctx.getBean(CharterLifecycleService.class)
                    .acceptCharter(admin.workspaceId(), agent.agentId()));
            assertEquals("ACTIVE", charterStatus(ctx, agent.agentId()));
            assertEquals(1, charterEventCount(ctx, agent.agentId(), "ACCEPT"));
            Integer reviewed = ctx.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM agent_agent WHERE id = ? AND reviewed_at IS NOT NULL",
                    Integer.class, agent.agentId());
            assertEquals(1, reviewed, "确认授权书刷新 reviewed_at");

            // 恢复后新 Turn 成功
            String threadId = createThread(ctx, principal(successor), agent.versionId());
            RuntimeApi.TurnHandle handle = as(ctx, successor, "member").run(() ->
                    startTurn(ctx, threadId, "cm-restored"));
            RuntimeApi.TurnView view = awaitStatus(ctx, principal(successor), handle.turnId(),
                    60_000, TurnStatus.COMPLETED);
            assertEquals(TurnStatus.COMPLETED, view.status(), () -> view.errorMessage());
        }
    }

    @Test
    void memberRemovedSuspendsOwnedAgentInWorkspace() {
        try (DualInstance di = DualInstance.start("three-tools", CHARTER_ENABLED)) {
            ConfigurableApplicationContext ctx = di.a();
            TestFixtures fixtures = ctx.getBean(TestFixtures.class);
            TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
            TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "member");
            TestFixtures.Actor bystander = fixtures.newLimitedUser(admin, "member");
            Seeded agent = seedAgent(ctx, owner.userId(), admin.userId(), admin.workspaceId(), List.of());
            Seeded other = seedAgent(ctx, bystander.userId(), admin.userId(), admin.workspaceId(), List.of());

            // 成员移除（真实入口）：该空间名下 Agent 暂停，他人 Agent 不受影响
            as(ctx, admin, "workspace_admin").run(() ->
                    ctx.getBean(WorkspaceMemberService.class).remove(admin.workspaceId(), owner.userId()));
            assertEquals("SUSPENDED", charterStatus(ctx, agent.agentId()));
            assertEquals(1, charterEventCount(ctx, agent.agentId(), "SUSPEND"));
            assertEquals("ACTIVE", charterStatus(ctx, other.agentId()));
            assertEquals(0, charterEventCount(ctx, other.agentId(), "SUSPEND"));

            // 同一事件经 MQ 消费者映射重复进入状态机 → 幂等
            IdentityCharterConsumer consumer = ctx.getBean(IdentityCharterConsumer.class);
            consumer.dispatch(envelope(admin.workspaceId(), owner.userId(),
                    "tioka.identity.member_removed.v1"), Map.of(
                    "workspaceId", admin.workspaceId(),
                    "userId", owner.userId(),
                    "source", "manual"));
            assertEquals(1, charterEventCount(ctx, agent.agentId(), "SUSPEND"),
                    "MQ 重复投递不重复写 SUSPEND 事件");
        }
    }

    // ---------- helpers ----------

    private record Seeded(String agentId, String versionId) {}

    private record RunCtx(PrincipalHolder principals, Principal principal) {
        <T> T run(Supplier<T> body) {
            return principals.runAs(principal, body);
        }

        void run(Runnable body) {
            principals.runAs(principal, body);
        }
    }

    private static RunCtx as(ConfigurableApplicationContext ctx, TestFixtures.Actor actor, String role) {
        return new RunCtx(ctx.getBean(PrincipalHolder.class), principal(actor, role));
    }

    private static Principal principal(TestFixtures.Actor actor) {
        return principal(actor, "member");
    }

    private static Principal principal(TestFixtures.Actor actor, String role) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of(role), Set.of("*"), "password");
    }

    private static String charterStatus(ConfigurableApplicationContext ctx, String agentId) {
        return ctx.getBean(JdbcTemplate.class).queryForObject(
                "SELECT charter_status FROM agent_agent WHERE id = ?", String.class, agentId);
    }

    private static int charterEventCount(ConfigurableApplicationContext ctx, String agentId, String kind) {
        Integer n = ctx.getBean(JdbcTemplate.class).queryForObject(
                "SELECT COUNT(*) FROM trust_charter_event WHERE agent_id = ? AND kind = ?",
                Integer.class, agentId, kind);
        return n == null ? 0 : n;
    }

    private static String createThread(ConfigurableApplicationContext ctx, Principal principal,
                                       String agentVersionId) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        RuntimeApi runtime = ctx.getBean(RuntimeApi.class);
        return principals.runAs(principal, () -> runtime.createThread(new RuntimeApi.CreateThreadCmd(
                agentVersionId, "chat", null, "chat", null)).threadId());
    }

    private static RuntimeApi.TurnHandle startTurn(ConfigurableApplicationContext ctx, String threadId,
                                                   String clientMsgId) {
        return ctx.getBean(RuntimeApi.class).startTurn(threadId, new RuntimeApi.StartTurnCmd(
                clientMsgId, List.of(RuntimeApi.InputItem.text("hi")), null, "chat", null));
    }

    private static RuntimeApi.TurnView awaitStatus(ConfigurableApplicationContext ctx, Principal principal,
                                                   String turnId, long timeoutMs, TurnStatus wanted) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        RuntimeApi runtime = ctx.getBean(RuntimeApi.class);
        long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeApi.TurnView last = null;
        while (System.currentTimeMillis() < deadline) {
            last = principals.runAs(principal, () -> runtime.getTurn(turnId));
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

    private static void disable(ConfigurableApplicationContext ctx, String userId) {
        UserAdminService userAdmin = ctx.getBean(UserAdminService.class);
        userAdmin.disable(userId);
    }

    private static EventEnvelope envelope(String workspaceId, String userId, String event) {
        return new EventEnvelope(UUID.randomUUID().toString(), event, 1, workspaceId,
                Instant.now().toString(), "owner-lifecycle-it", null, null, null,
                null, "user:" + userId, userId + ":" + workspaceId, 0, null, Map.of());
    }

    /**
     * 以 owner 身份播种已发布 Agent（owner_id 由 INSERT 填充落为 owner）。mcpTools 非空时
     * 由 admin 注册 GateCluster MCP 桩并授予 owner USE（写工具不绑定，避免 CharterPublishCheck）。
     */
    private Seeded seedAgent(ConfigurableApplicationContext ctx, String ownerUserId,
                             String adminUserId, String workspaceId, List<String> mcpTools) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        ModelAdminService models = ctx.getBean(ModelAdminService.class);
        AgentService agents = ctx.getBean(AgentService.class);
        String tag = "tru03-" + UUID.randomUUID().toString().substring(0, 8);
        String ws = workspaceId;
        Principal ownerP = new Principal(ownerUserId, ws, PrincipalType.USER,
                Set.of("member"), Set.of("*"), "password");
        Principal adminP = new Principal(adminUserId, ws, PrincipalType.USER,
                Set.of("system_admin"), Set.of("*"), "password");

        String planId = principals.runAs(adminP, () -> {
            var provider = models.createProvider(new CreateProviderRequest(tag + "-p",
                    ctx.getEnvironment().getProperty("tioka.gate.llm-base-url"), "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
            String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
            String plan = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
            models.publish(plan);
            return plan;
        });

        List<AgentSpec.ToolSet.McpBinding> mcp = List.of();
        if (!mcpTools.isEmpty()) {
            McpAdminService mcpAdmin = ctx.getBean(McpAdminService.class);
            McpStore.McpServer server = principals.runAs(adminP, () -> {
                McpStore.McpServer s = mcpAdmin.register(tag + "-mcp",
                        ctx.getEnvironment().getProperty("tioka.gate.mcp-base-url"), "tok");
                mcpAdmin.refreshTools(s.id());
                for (String t : mcpTools) {
                    mcpAdmin.updateToolRisk(s.id(), t, "erp.slow".equals(t) ? "READ" : "WRITE");
                }
                return s;
            });
            mcp = List.of(new AgentSpec.ToolSet.McpBinding(server.id(), mcpTools));
        }
        List<AgentSpec.ToolSet.McpBinding> mcpBindings = mcp;

        return principals.runAs(ownerP, () -> {
            var agent = agents.create(tag + "-agent", ws);
            AgentSpec spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "hi"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    new AgentSpec.ToolSet(List.of(), mcpBindings, List.of(), List.of()),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                    null, null, null, null, null, null, null, null);
            var published = agents.publishSpec(agent.id(), spec);
            return new Seeded(agent.id(), published.versionId());
        });
    }
}
