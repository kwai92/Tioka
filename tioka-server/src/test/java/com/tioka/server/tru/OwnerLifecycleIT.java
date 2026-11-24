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

            RuntimeApi.TurnView view;
            try {
                view = awaitStatus(ctx, principal(successor), handle.turnId(),
                        30_000, TurnStatus.CANCELLED);
            } catch (AssertionError e) {
                // ⑤ 取证（2026-11-24）：本用例偶发 last=FAILED/INTERNAL/故障接力链耗尽:
                // script exhausted: mcp-slow-cancel——即取消信号未在「turn 下一次 LLM 调用前」到达，
                // agent loop 续跑发起第 2 次 chat，而剧本只有 1 条 call → 500 → turn FAILED 而非 CANCELLED。
                // awaitStatus 的消息只有终态，无法区分两个互斥根因，故在此附取证：
                // 「授权书根本没暂停（事件丢失=真 bug）」vs「已暂停但 turn 抢先续跑（竞态）」。
                throw new AssertionError(e.getMessage()
                        + diagnoseCancelNotPropagated(ctx, agent.agentId(), handle.turnId()), e);
            }
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

    /**
     * ⑤「运行中 Turn 未能在禁用负责人后到达 CANCELLED」的取证辅助（2026-11-24）。
     *
     * <p>{@code awaitStatus} 的失败消息只有终态（如 {@code FAILED/INTERNAL/故障接力链耗尽:
     * script exhausted}），无法区分三个互斥根因。本方法用<b>既有的库表时序</b>把三者分开，
     * 不需改桩、不需新增字段：
     * <ol>
     *   <li><b>授权书根本没暂停</b>（事件丢失 = 真 bug）：{@code agent_agent.charter_status} 仍为
     *       {@code ACTIVE}、且 {@code trust_charter_event} 无 SUSPEND 行。此时 turn 走 FAILED 是因为
     *       没人通知它停，属功能缺陷。</li>
     *   <li><b>已暂停但 turn 抢先续跑</b>（竞态，本用例最可能形态）：SUSPEND 已记录，但其
     *       {@code create_at} <b>晚于</b>该 turn 的<b>第 2 次</b> {@code model_usage} 的 {@code create_at}
     *       —— 即慢工具返回后 agent loop 已发起下一次 LLM 调用，取消信号还没到，剧本（仅 1 条 call）
     *       被调超 → 500 → FAILED。</li>
     *   <li><b>已暂停、且暂停早于第 2 次调用，但 turn 仍 FAILED</b>：说明取消信号未被运行中的
     *       agent loop 消费（传播链断点），是比竞态更严重的问题。</li>
     * </ol>
     *
     * <p>取证手段的自洽性（吸取 ② 的教训：诊断字段在目标失败形态下必须真的有值）：本方法只依赖
     * {@code model_usage}（turn 的每次 LLM 调用一条）、{@code trust_charter_event}（SUSPEND 一条）、
     * {@code runtime_turn}（终态与时间戳），三者都是本用例正常路径必然会写的既有数据，
     * 故 FAILED 形态下它们同样存在、可查——不会像 ② 那样退化成一个恒 null 的字段。
     *
     * <p>约束：<b>不向 SQL 传 {@code Instant}/时间参数</b>（全仓无 {@code JdbcTemplate} 直传 {@code Instant}
     * 的先例，TIMESTAMPTZ ↔ Java 时间桥接是本项目已踩的坑）；仅按 VARCHAR 的 {@code agent_id}/{@code turn_id}
     * 过滤，时间戳一律 {@code SELECT} 出来打印、由读日志者比对。整体 try/catch，<b>诊断自身抛异常
     * 不得掩盖原始断言失败</b>。
     */
    private static String diagnoseCancelNotPropagated(ConfigurableApplicationContext ctx,
                                                      String agentId, String turnId) {
        try {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            StringBuilder sb = new StringBuilder();

            String charterStatus = jdbc.queryForObject(
                    "SELECT charter_status FROM agent_agent WHERE id = ?", String.class, agentId);
            List<Map<String, Object>> suspendEvents = jdbc.queryForList(
                    "SELECT kind, reason, create_at FROM trust_charter_event"
                            + " WHERE agent_id = ? AND kind = 'SUSPEND' ORDER BY create_at, id", agentId);
            sb.append("\n  [诊断] agent=").append(agentId)
                    .append(" charter_status=").append(charterStatus)
                    .append("（期望 SUSPENDED）；SUSPEND 事件数=").append(suspendEvents.size());
            for (Map<String, Object> e : suspendEvents) {
                sb.append("\n      SUSPEND reason=").append(e.get("reason"))
                        .append(" create_at=").append(e.get("create_at"));
            }

            List<Map<String, Object>> usages = jdbc.queryForList(
                    "SELECT purpose, status, model_ref, create_at FROM model_usage"
                            + " WHERE turn_id = ? ORDER BY create_at, id", turnId);
            sb.append("\n  [诊断] turn=").append(turnId).append(" 的 model_usage 共 ")
                    .append(usages.size()).append(" 条（剧本 mcp-slow-cancel 只有 1 条 call，"
                            + ">1 即说明 agent loop 续跑发起了额外 LLM 调用）：");
            for (Map<String, Object> u : usages) {
                sb.append("\n      ").append(u.get("purpose")).append('/').append(u.get("status"))
                        .append(" model_ref=").append(u.get("model_ref"))
                        .append(" create_at=").append(u.get("create_at"));
            }

            List<Map<String, Object>> turn = jdbc.queryForList(
                    "SELECT status, error_code, started_at, ended_at FROM runtime_turn WHERE id = ?",
                    turnId);
            sb.append("\n  [诊断] runtime_turn 终态：").append(turn);

            // 直接给出可判读的时序结论（避免读日志者自己比对时间戳出错）
            sb.append("\n  [诊断] 时序判读：");
            if (!"SUSPENDED".equals(charterStatus) || suspendEvents.isEmpty()) {
                sb.append("授权书未暂停（charter_status=").append(charterStatus)
                        .append(", SUSPEND 事件=").append(suspendEvents.size())
                        .append("）⇒ 指向【根因1：身份事件丢失/未消费】，属功能缺陷而非竞态。");
            } else if (usages.size() <= 1) {
                sb.append("授权书已暂停且 turn 未发起第 2 次 LLM 调用，却仍未 CANCELLED ⇒ "
                        + "指向【根因3：取消信号未被运行中的 loop 消费】，需查传播链。");
            } else {
                Object suspendAt = suspendEvents.get(0).get("create_at");
                Object secondCallAt = usages.get(1).get("create_at");
                sb.append("授权书已暂停；SUSPEND create_at=").append(suspendAt)
                        .append(" 对比 第2次LLM调用 create_at=").append(secondCallAt).append(' ');
                if (String.valueOf(secondCallAt).compareTo(String.valueOf(suspendAt)) < 0) {
                    sb.append("⇒ 第2次调用早于暂停 ⇒【根因2：竞态，turn 抢在取消前续跑耗尽剧本】。");
                } else {
                    sb.append("⇒ 暂停早于第2次调用但 turn 仍续跑 ⇒【根因3：取消未送达 loop】，比竞态更严重。");
                }
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "\n  [诊断] 取证查询自身失败（不影响上述断言结论）: " + e;
        }
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
