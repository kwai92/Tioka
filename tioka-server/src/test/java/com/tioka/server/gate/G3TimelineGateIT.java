package com.tioka.server.gate;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.timeline.TimelineCategory;
import com.tioka.governance.api.timeline.TimelineEntryView;
import com.tioka.governance.api.timeline.TimelinePageSpec;
import com.tioka.governance.api.timeline.TimelineQuery;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.system.api.AuditController;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.obs.ExtInteractionQueryApi;
import com.tioka.system.api.obs.ExtInteractionRecorder;
import com.tioka.system.api.obs.ExtInteractionView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸门 G3：统一时间线证据链（OBS-06〜09 / ADR-075）。
 *
 * <p>步骤（与类注释一致）：
 * <ol>
 *   <li>一份合同从推送到通知在一条时间线；</li>
 *   <li>外发记录可列出；导出审计留痕。</li>
 * </ol>
 *
 * <p>实现口径（生产设施，无 mock；空间随机前缀隔离）：
 * <ul>
 *   <li>RUN / MODEL / TOOL 由真实对话轮产生：运行清单写入即投影 {@code RUN}（OBS-06）；
 *       {@code model_usage}（模型调用）与 {@code runtime_item}（工具调用按步合并）由各自写入点
 *       经 {@code TimelineProjector} 写入即投影 {@code MODEL}/{@code TOOL}（OBS-08 偏离收口），
 *       {@link TimelineQuery#rebuild} 仅作按时间段补链（ADR-075 §2「可按时间段重建」）；</li>
 *   <li>INBOUND / OUTBOUND / NOTIFY 来自外部交互台账（OBS-07）：一份合同经
 *       {@code ExtInteractionRecorder} 落台账（写入即投影），其 {@code subject=contract:<id>}
 *       在主体时间线汇成「推送 → 执行 → 通知」一条链；</li>
 *   <li>导出审计（OBS-09）：{@code AuditController.export} 自带 {@code audit.export} 留痕。</li>
 * </ul>
 */
@Tag("gate")
class G3TimelineGateIT {

    @Test
    void contractPushToNotifyOneTimeline_ledgerListable_exportAudited() throws Exception {
        String script = "three-tools";
        try (GateCluster cluster = GateCluster.start(1, script, Map.of(), false)) {
            ConfigurableApplicationContext ctx = cluster.a();
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(ctx, cluster.llm().baseUrl());
            PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
            Principal user = GateSeed.user(bundle.userId(), bundle.workspaceId());
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            Instant from = Instant.now().minus(Duration.ofMinutes(30));

            // —— 1. 真实对话轮：3 次 echo 工具调用 → 内核产出 RUN / MODEL / TOOL 事实 ——
            RuntimeApi.ThreadView thread = GateSeed.createThread(ctx, bundle);
            RuntimeApi.TurnHandle handle = GateSeed.startTurn(ctx, bundle, thread.threadId(),
                    "cm-g3-timeline-1", "把合同推送到外部 CRM 并发送通知");
            RuntimeApi.TurnView turn = GateSeed.awaitTerminal(ctx, bundle, handle.turnId(), 120_000);
            assertEquals(TurnStatus.COMPLETED, turn.status(), () -> turn.errorMessage());

            String requestId = jdbc.queryForObject(
                    "SELECT request_id FROM runtime_turn WHERE id = ?", String.class, handle.turnId());
            assertNotNull(requestId, "Turn 须带 request_id（OBS-03 / ADR-073）");
            // 模型用量异步批量落库（UsageRecorder 每 1s flush）
            awaitAtLeast(jdbc, "SELECT count(*) FROM model_usage WHERE turn_id = ?",
                    handle.turnId(), 1, 30_000);
            Long toolItems = jdbc.queryForObject(
                    "SELECT count(*) FROM runtime_item WHERE turn_id = ? AND type IN ('tool_call','tool_result')",
                    Long.class, handle.turnId());
            assertTrue(toolItems != null && toolItems > 0, "Turn 应产生工具运行项");

            // —— 1b. 写入即投影（OBS-08 偏离收口）：Turn 内 MODEL / TOOL 无需 rebuild 即已入时间线 ——
            awaitAtLeast(jdbc, "SELECT count(*) FROM timeline_entry WHERE request_id = ? AND category = 'MODEL'",
                    requestId, 1, 30_000);
            awaitAtLeast(jdbc, "SELECT count(*) FROM timeline_entry WHERE request_id = ? AND category = 'TOOL'",
                    requestId, 1, 30_000);
            TimelineQuery timeline = ctx.getBean(TimelineQuery.class);
            TimelinePageSpec spec = new TimelinePageSpec(Set.of(), null, null, null, 200, null, null);
            Set<TimelineCategory> writeTimeCats = principals.runAs(user,
                            () -> timeline.pageByRequest(requestId, spec)).stream()
                    .map(TimelineEntryView::category).collect(Collectors.toSet());
            assertTrue(writeTimeCats.containsAll(Set.of(TimelineCategory.MODEL, TimelineCategory.TOOL)),
                    () -> "Turn 内 MODEL / TOOL 须在写入期即投影（不依赖 rebuild），实际=" + writeTimeCats);

            // —— 2. 一份合同跨边界：推送（入站）→ 执行（出站）→ 通知 ——
            String contractId = "HT-" + UUID.randomUUID().toString().substring(0, 8);
            String subject = "contract:" + contractId;
            String externalRef = "EXT-" + contractId;
            ExtInteractionRecorder ledger = ctx.getBean(ExtInteractionRecorder.class);
            principals.runAs(user, () -> {
                ledger.record(new ExtInteractionRecorder.ExtInteractionRecord(
                        ExtInteractionRecorder.Direction.IN, "contract", "crm", null, "contract.push",
                        ExtInteractionRecorder.Status.OK, 202, externalRef, null, 12L,
                        Map.of("request_id", requestId, "subject", subject, "actor_id", bundle.userId())));
                ledger.record(new ExtInteractionRecorder.ExtInteractionRecord(
                        ExtInteractionRecorder.Direction.OUT, "biz", "crm", null, "contract.execute",
                        ExtInteractionRecorder.Status.OK, 200, null, null, 30L,
                        Map.of("request_id", requestId, "subject", subject)));
                ledger.record(new ExtInteractionRecorder.ExtInteractionRecord(
                        ExtInteractionRecorder.Direction.OUT, "notify", "im", null, "im.message.send",
                        ExtInteractionRecorder.Status.OK, 200, "msg-" + contractId, null, 20L,
                        Map.of("request_id", requestId, "subject", subject)));
                return null;
            });

            // —— 3. 按时间段重建：补链出口仍可达（幂等，覆盖写入时的近似时刻）（ADR-075 §2）——
            Instant to = Instant.now().plus(Duration.ofMinutes(30));
            int rebuilt = principals.runAs(user, () -> timeline.rebuild(from, to));
            assertTrue(rebuilt > 0, "重建应至少投影一条来源事件");

            // —— 4. 断言①：请求时间线——同一 request_id 上覆盖合同全链 ——
            List<TimelineEntryView> byRequest =
                    principals.runAs(user, () -> timeline.pageByRequest(requestId, spec));
            Set<TimelineCategory> requestCats = byRequest.stream()
                    .map(TimelineEntryView::category).collect(Collectors.toSet());
            assertTrue(requestCats.containsAll(Set.of(TimelineCategory.RUN, TimelineCategory.MODEL,
                            TimelineCategory.TOOL, TimelineCategory.INBOUND, TimelineCategory.OUTBOUND,
                            TimelineCategory.NOTIFY)),
                    () -> "请求时间线应覆盖合同全链，实际=" + requestCats);

            // —— 5. 断言①（续）：合同主体时间线「从推送到通知在一条时间线」——
            List<TimelineEntryView> bySubject =
                    principals.runAs(user, () -> timeline.pageBySubject("contract", contractId, spec));
            Set<TimelineCategory> subjectCats = bySubject.stream()
                    .map(TimelineEntryView::category).collect(Collectors.toSet());
            assertTrue(subjectCats.containsAll(Set.of(TimelineCategory.INBOUND, TimelineCategory.OUTBOUND,
                            TimelineCategory.NOTIFY)),
                    () -> "合同主体时间线应含推送 / 执行 / 通知，实际=" + subjectCats);

            // —— 6. 断言②：外发记录可列出（出站明细 + 「输入对方单号，找到我方全过程」）——
            ExtInteractionQueryApi ledgerQuery = ctx.getBean(ExtInteractionQueryApi.class);
            List<ExtInteractionView> outbound = ledgerQuery.query(new ExtInteractionQueryApi.Query(
                    null, subject, null, null, null, "OUT", null, null, 50, 0));
            assertEquals(2, outbound.size(), "合同应有 2 条出站外发记录（执行 + 通知）");
            assertTrue(outbound.stream().anyMatch(v -> "notify".equals(v.kind())), "通知应可列出");
            List<ExtInteractionView> byRef = ledgerQuery.query(new ExtInteractionQueryApi.Query(
                    null, null, null, externalRef, null, null, null, null, 50, 0));
            assertEquals(1, byRef.size(), "按对方单号应能定位我方推送记录");

            // —— 7. 断言②（续）：导出审计留痕（OBS-09）——
            principals.runAs(user, () -> {
                try {
                    ctx.getBean(AuditController.class).export(null, null, null, null, null, null, null,
                            null, null, null, null, null, new MockHttpServletResponse());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            assertFalse(ctx.getBean(AuditApi.class).query(new AuditApi.AuditQuery(
                            null, null, "audit.export", "audit_event", null, AuditOutcome.SUCCESS,
                            null, null, null, 50)).isEmpty(),
                    "导出应写 audit.export SUCCESS 留痕");
        }
    }

    private static void awaitAtLeast(JdbcTemplate jdbc, String sql, Object arg, long min, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Long count = null;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(sql, Long.class, arg);
            if (count != null && count >= min) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("等待落库超时（" + timeoutMs + "ms）: " + sql + " count=" + count);
    }
}
