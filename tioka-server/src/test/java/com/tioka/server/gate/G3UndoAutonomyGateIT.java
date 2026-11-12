package com.tioka.server.gate;

import com.tioka.agent.application.AgentService;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyContext;
import com.tioka.governance.api.autonomy.AutonomyDecision;
import com.tioka.governance.api.autonomy.AutonomyGate;
import com.tioka.governance.api.autonomy.AutonomyLevel;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.GateDecision;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolInvocation;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.application.tool.DailyCounterStore;
import com.tioka.runtime.application.trust.UndoService;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.support.TestFixtures;
import com.tioka.testkit.FakeErp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 G3 闸门（M3-05 §10）：撤销与自治降级。双实例、Keycloak、开启鉴权
 * （{@code GateCluster.start(2, script, props, true)}），一条用例串起 5 个步骤：
 *
 * <ol>
 *   <li>L2 下 FakeErp 5 步写操作（真实 preview/apply，落 {@code trust_action} APPLIED）</li>
 *   <li>请求人撤销该 Turn → 补偿消费者倒序复原、当日累计扣回、状态全 COMPENSATED</li>
 *   <li>动作类别降为 L1、负责人收到 NTF-01 降级卡片、下一次同类调用按新等级转审批</li>
 *   <li>1h 内再撤销两次 → 熔断开启 → 全部类别 L1</li>
 *   <li>注入补偿失败 → 停止后续补偿、生成负责人人审、全部类别 L1</li>
 * </ol>
 *
 * <p><b>口径登记</b>：①Runtime 的 {@code ToolCallContext} 不含 orchRunId、生产
 * {@code trust_action.orch_run_id} 无写入点，故「单 Run」以 turnId 表达、撤销用
 * {@code scope=turn}（M3-05 §6 允许「编排 Run 或 Turn」）；②5 步写操作由 FakeErp
 * 的 {@code preview/apply} 真实产生并经 {@code PreparedCallStore} 落库，不经 LLM 剧本
 * 驱动（闸门聚焦撤销/降级链，工具循环由 G0/G1 闸门覆盖）；③补偿与降级事件由 in-memory
 * transport 按 outbox 投递给真实消费者，断言用轮询等待最终状态。
 */
@Tag("gate")
class G3UndoAutonomyGateIT {

    private static final String CLASS_WRITE_OFF = "ar.writeoff";
    private static final String CLASS_REVIEW = "contract.review";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void undoDemotionCircuitAndCompensationFailure() {
        try (Scene s = new Scene()) {
            // ---------- 步骤 1：L2 下 5 步写操作 ----------
            String turn1 = s.newTurnId();
            Map<String, Map<String, Object>> before = s.writeSteps(turn1, 5);
            assertEquals(5, before.size());
            assertEquals(5, s.countActions(turn1, "APPLIED"));

            // ---------- 步骤 2：撤销该 Turn ----------
            UndoService.UndoResult result = s.undo(turn1);
            assertEquals(5, result.compensating().size(), "5 步全部进入补偿");
            await(() -> s.countActions(turn1, "COMPENSATED") == 5,
                    "5 步补偿未全部完成: " + turn1);
            for (Map.Entry<String, Map<String, Object>> e : before.entrySet()) {
                assertEquals(e.getValue(), s.erp.order(e.getKey()), "FakeErp 未复原: " + e.getKey());
            }
            assertEquals(0L, s.dailyRecords(), "当日累计应扣回 5 条");

            // ---------- 步骤 3：降级 + 通知 + 下一次转审批 ----------
            await(() -> s.levelOf(CLASS_WRITE_OFF) == 1, "撤销后 ar.writeoff 应降为 L1");
            assertTrue(s.demotionEvents() >= 1, "应记录降级幂等台账");
            await(() -> s.demotionNotices() >= 1,
                    "降级卡片未投递给负责人（或空间兜底收件人）");
            AutonomyDecision next = s.autonomyGate().decide(new AutonomyContext(
                    PolicyDecision.ALLOW, "chat", 1, "REVERSIBLE", false, "WRITE",
                    false, null, s.agentId, s.agentVersionId, CLASS_WRITE_OFF, false, false));
            assertEquals(AutonomyDecision.REQUIRE_APPROVAL, next,
                    "L1 下同类调用必须转审批");

            // ---------- 步骤 4：1h 内再撤销两次 → 熔断 → 全部类别 L1 ----------
            String turn2 = s.newTurnId();
            s.writeSteps(turn2, 1);
            s.undo(turn2);
            await(() -> s.countActions(turn2, "COMPENSATED") == 1, "turn2 补偿未完成");
            String turn3 = s.newTurnId();
            s.writeSteps(turn3, 1);
            s.undo(turn3);
            await(() -> s.countActions(turn3, "COMPENSATED") == 1, "turn3 补偿未完成");
            await(() -> s.circuitOpened(), "第 3 次撤销应开启熔断");
            await(() -> s.levelOf(CLASS_REVIEW) == 1, "熔断后 contract.review 应降为 L1");
            assertEquals(1, s.levelOf(CLASS_WRITE_OFF));

            // ---------- 步骤 5：注入补偿失败 ----------
            // 步骤 3/4 已把两个类别降到 L1，直接断言「全部 L1」会是恒真断言。
            // 先把 contract.review 抬回 L3，让本步降级链路真的有活可干；
            // 同时记录台账/通知基线，用增量而非累计值证明本步确实产生了降级。
            int noticesBefore = s.demotionNotices();
            int demotionsBefore = s.demotionEvents();
            s.rearmLevel(CLASS_REVIEW, AutonomyLevel.L3);
            assertEquals(3, s.levelOf(CLASS_REVIEW), "步骤 5 前置等级应重新抬到 L3");

            String turn4 = s.newTurnId();
            s.writeSteps(turn4, 2);
            s.erp.setCompensateFail(true);
            s.undo(turn4);
            await(() -> "FAILED".equals(s.undoStatus(turn4)), "补偿失败后撤销组应 FAILED");
            assertTrue(s.countActions(turn4, "COMPENSATION_FAILED") >= 1,
                    "失败动作应标记 COMPENSATION_FAILED");
            assertTrue(s.ownerAttentionCards() >= 1, "补偿失败应生成负责人人审卡片");
            await(() -> s.levelOf(CLASS_REVIEW) == 1 && s.levelOf(CLASS_WRITE_OFF) == 1,
                    "补偿失败后全部类别应为 L1");
            assertTrue(s.demotionEvents() > demotionsBefore, "补偿失败应新增降级台账行");
            assertTrue(s.demotionNotices() > noticesBefore, "补偿失败应新增降级通知卡片");
            assertEquals(1, s.countActions(turn4, "COMPENSATING"),
                    "撤销组 FAILED 后应停止后续补偿（未失败动作保持 COMPENSATING）");
        }
    }

    // ---------- 等待与场景 ----------

    private static void await(Supplier<Boolean> condition, String message) {
        long deadline = System.currentTimeMillis() + 45_000L;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.get())) {
                    return;
                }
            } catch (RuntimeException e) {
                // 表尚未就绪 / 事务未提交：继续等待，但保留最后一次异常，
                // 避免把 SQL 语法错、缺列等真实缺陷吞成一句笼统超时。
                last = e;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError(message, last);
    }

    private static final class Scene implements AutoCloseable {
        final GateCluster cluster;
        final FakeErp erp = new FakeErp();
        final TestFixtures.Actor admin;
        final TestFixtures.Actor owner;
        final String workspaceId;
        final String agentId;
        final String agentVersionId;

        Scene() {
            cluster = GateCluster.start(2, "three-tools", Map.of(
                    "tioka.trust.charter.enabled", "true",
                    "tioka.trust.autonomy.enabled", "true",
                    "tioka.trust.blast-radius.enabled", "true",
                    // NTF-01 两个开关：平台装配（类级 @ConditionalOnProperty）+ M2 端口桥接；
                    // 只开 bridge 会让 NotificationPort 缺席、降级卡片静默跳过。
                    "tioka.notify.enabled", "true",
                    "tioka.notify.bridge.enabled", "true",
                    "tioka.notify.noop-channel.enabled", "true",
                    // 撤销/补偿/降级/通知全链路靠 outbox → in-memory transport 投递；
                    // gate 默认关中继（避免与 LeaseReaper 串扰），本闸门显式开启
                    "tioka.messaging.relay-enabled", "true"), true);
            ConfigurableApplicationContext ctx = cluster.a();
            TestFixtures fixtures = ctx.getBean(TestFixtures.class);
            admin = fixtures.newActor("workspace_admin");
            owner = fixtures.newLimitedUser(admin, "member");
            workspaceId = admin.workspaceId();
            String tag = "g3und-" + UUID.randomUUID().toString().substring(0, 8);
            agentVersionId = "av_" + tag;
            // 以业务成员身份创建 Agent → agent_agent.owner_id 即负责人，降级通知收件人
            agentId = asOwner(() -> ctx.getBean(AgentService.class)
                    .create(tag + "-agent", workspaceId).id());
            // 两个实例的注册表都装载同一个 FakeErp（补偿消费者在 outbox 所在实例执行）
            for (int i = 0; i < cluster.size(); i++) {
                cluster.instance(i).getBean(ToolRegistry.class).register(erp);
            }
            // 初始等级：核销 L2、另一个类别 L3（验证「全部类别降到 L1」）
            AutonomyApi autonomy = ctx.getBean(AutonomyApi.class);
            autonomy.setLevel(agentId, CLASS_WRITE_OFF, AutonomyLevel.L2, "gate seed");
            autonomy.setLevel(agentId, CLASS_REVIEW, AutonomyLevel.L3, "gate seed");
        }

        ConfigurableApplicationContext ctx() {
            return cluster.a();
        }

        Principal adminPrincipal() {
            return new Principal(admin.userId(), workspaceId, PrincipalType.USER,
                    Set.of("workspace_admin"), Set.of("*"), "password");
        }

        Principal ownerPrincipal() {
            return new Principal(owner.userId(), workspaceId, PrincipalType.USER,
                    Set.of("member"), Set.of("*"), "password");
        }

        <T> T asAdmin(Supplier<T> body) {
            return ctx().getBean(PrincipalHolder.class).runAs(adminPrincipal(), body);
        }

        <T> T asOwner(Supplier<T> body) {
            return ctx().getBean(PrincipalHolder.class).runAs(ownerPrincipal(), body);
        }

        String newTurnId() {
            return "trn_" + UUID.randomUUID().toString().substring(0, 12);
        }

        /** 真实的 n 步写操作：FakeErp preview/apply + PreparedCallStore 落库。 */
        Map<String, Map<String, Object>> writeSteps(String turnId, int n) {
            Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
            List<ChangeSet> changeSets = new ArrayList<>();
            List<ChangeSet.ApplyReceipt> receipts = new ArrayList<>();
            List<ToolInvocation> invocations = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                String orderId = "ord_" + turnId + "_" + i;
                erp.seedOrder(orderId, "OPEN", 1_000_000L * i);
                snapshot.put(orderId, erp.order(orderId));
                ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_UPDATE_ORDER,
                        Map.of("orderId", orderId, "status", "SHIPPED"));
                ChangeSet cs = erp.preview(inv);
                invocations.add(inv);
                changeSets.add(cs);
                receipts.add(erp.apply(inv, cs, "idem-" + turnId + "-" + i));
            }
            return asAdmin(() -> {
                PreparedCallStore store = ctx().getBean(PreparedCallStore.class);
                DailyCounterStore counters = ctx().getBean(DailyCounterStore.class);
                ToolGateway.ToolCallContext call = new ToolGateway.ToolCallContext(
                        adminPrincipal(), "thr_" + turnId, turnId, agentVersionId, "chat",
                        List.of(), Map.of(), null);
                long total = 0;
                for (int i = 0; i < n; i++) {
                    TrustActionDO row = store.insertProposed(call, agentId,
                            FakeErp.TOOL_UPDATE_ORDER, CLASS_WRITE_OFF,
                            ToolRisk.WRITE, Reversibility.REVERSIBLE, GateDecision.AUTO_COMMIT,
                            invocations.get(i), changeSets.get(i), "itm_" + turnId + "_" + i);
                    store.markApplied(row, receipts.get(i));
                    total += changeSets.get(i).totals() == null ? 0
                            : changeSets.get(i).totals().amountMicros();
                }
                // 模拟 commit 路径已记入当日累计，撤销后应被扣回
                counters.add(agentId, CLASS_WRITE_OFF, LocalDate.now(ZONE), total, n);
                return snapshot;
            });
        }

        UndoService.UndoResult undo(String turnId) {
            return asAdmin(() -> ctx().getBean(UndoService.class)
                    .undo("turn", turnId, adminPrincipal()));
        }

        long dailyRecords() {
            return ctx().getBean(DailyCounterStore.class)
                    .get(agentId, CLASS_WRITE_OFF, LocalDate.now(ZONE)).records();
        }

        int levelOf(String actionClass) {
            return ctx().getBean(AutonomyApi.class).levelOf(agentId, actionClass);
        }

        void rearmLevel(String actionClass, AutonomyLevel level) {
            ctx().getBean(AutonomyApi.class)
                    .setLevel(agentId, actionClass, level, "gate step5 re-arm");
        }

        AutonomyGate autonomyGate() {
            return ctx().getBean(AutonomyGate.class);
        }

        int countActions(String turnId, String status) {
            Integer n = ctx().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM trust_action WHERE turn_id = ? AND status = ?",
                    Integer.class, turnId, status);
            return n == null ? 0 : n;
        }

        String undoStatus(String turnId) {
            List<String> rows = ctx().getBean(JdbcTemplate.class).queryForList(
                    "SELECT status FROM trust_undo_request WHERE scope_type = 'turn' AND scope_id = ?",
                    String.class, turnId);
            return rows.isEmpty() ? "" : rows.get(0);
        }

        boolean circuitOpened() {
            List<Object> rows = ctx().getBean(JdbcTemplate.class).queryForList(
                    "SELECT opened_at FROM trust_undo_circuit WHERE agent_id = ?",
                    Object.class, agentId);
            return !rows.isEmpty() && rows.get(0) != null;
        }

        int demotionEvents() {
            Integer n = ctx().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM trust_autonomy_demotion WHERE agent_id = ?",
                    Integer.class, agentId);
            return n == null ? 0 : n;
        }

        /** 只认投递给负责人的卡片；负责人在消费侧取不到时退化为 workspace:{id} 兜底。 */
        int demotionNotices() {
            Integer n = ctx().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM ntf_delivery WHERE dedup_key LIKE 'autonomy-demote:%' "
                            + "AND (recipient = ? OR recipient = ?)",
                    Integer.class, owner.userId(), "workspace:" + workspaceId);
            return n == null ? 0 : n;
        }

        int ownerAttentionCards() {
            Integer n = ctx().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM runtime_approval WHERE kind = 'card' "
                            + "AND title LIKE '补偿失败%'", Integer.class);
            return n == null ? 0 : n;
        }

        @Override
        public void close() {
            cluster.close();
        }
    }
}
