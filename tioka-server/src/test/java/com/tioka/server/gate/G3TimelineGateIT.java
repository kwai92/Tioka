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
                    () -> "请求时间线应覆盖合同全链，实际=" + requestCats
                            + diagnoseRequestChain(jdbc, requestId, subject, from, to));

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

    /**
     * ④-b 偶发失败的取证辅助（2026-11-24）：断言「请求时间线覆盖合同全链」失败时，
     * 一次性区分三种互斥可能，避免只凭「实际=[MODEL, TOOL, RUN]」猜测根因：
     * <ol>
     *   <li><b>台账未落库</b> —— {@code ext_interaction} 里本 subject 的行数 < 3，
     *       说明 {@code DefaultExtInteractionRecorder.record()} 写入失败被其 catch 吞掉
     *       （该类 :59-62 只 log.warn 不抛，javadoc :28「台账失败只记日志不阻断主流程」）；</li>
     *   <li><b>台账已落库但未投影</b> —— 行存在而 {@code timeline_entry} 中
     *       {@code source_type='ext_interaction'} 的条目缺失，指向 rebuild 的
     *       {@code eventsInRange} 取数或 {@code DefaultTimelineProjector.project} 环节；</li>
     *   <li><b>投影后被删</b> —— {@code deleteBySourceRange} 只按 {@code source_type} + 时间窗口删除、
     *       不带空间或 request 维度过滤（{@code TimelineEntryMapper.xml:100-107}），
     *       若同一进程内其它用例在窗口内先后 rebuild，会删掉本用例刚投影的条目。</li>
     * </ol>
     *
     * <p>设计约束（勿改回带时间参数的查询）：
     * <ul>
     *   <li><b>不向 SQL 传 {@code Instant}/时间参数</b>。全仓无 {@code JdbcTemplate} 直传
     *       {@code Instant} 的先例（生产 {@code AgentSpecBackfill:105} 用的是
     *       {@code PreparedStatement.setTimestamp}），而 TIMESTAMPTZ ↔ Java 时间类型的桥接
     *       正是本项目已踩过的坑；诊断代码若因参数绑定失败而抛异常，恰好在最需要它时失效。
     *       故改为**打印实际 {@code ts} / {@code occurred_at} 值**并附上 rebuild 窗口的
     *       ISO-8601 字串，由读日志的人/会话直接比对窗口是否覆盖。</li>
     *   <li>只在断言失败时由 JUnit 的 {@code Supplier} 惰性调用，正常路径零开销。</li>
     *   <li>整体 try/catch，<b>诊断自身抛异常不得掩盖原始断言失败</b>。</li>
     * </ul>
     */
    private static String diagnoseRequestChain(JdbcTemplate jdbc, String requestId, String subject,
                                               Instant from, Instant to) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n  [诊断] rebuild 窗口 = [").append(from).append(" .. ").append(to).append(']');

            List<Map<String, Object>> ledger = jdbc.queryForList(
                    "SELECT direction, kind, operation, ts, request_id FROM ext_interaction"
                            + " WHERE subject = ? ORDER BY ts, id", subject);
            sb.append("\n  [诊断] ext_interaction 台账行（subject=").append(subject).append("）共 ")
                    .append(ledger.size()).append(" 行（期望 3：IN/contract、OUT/biz、OUT/notify）：");
            for (Map<String, Object> r : ledger) {
                sb.append("\n      ").append(r.get("direction")).append('/').append(r.get("kind"))
                        .append(' ').append(r.get("operation"))
                        .append(" ts=").append(r.get("ts"))
                        .append(" request_id=").append(r.get("request_id"));
            }

            List<Map<String, Object>> entries = jdbc.queryForList(
                    "SELECT category, source_type, source_event_id, occurred_at"
                            + " FROM timeline_entry WHERE request_id = ? ORDER BY occurred_at, id",
                    requestId);
            sb.append("\n  [诊断] timeline_entry（request_id=").append(requestId)
                    .append("）共 ").append(entries.size()).append(" 行：");
            for (Map<String, Object> e : entries) {
                sb.append("\n      category=").append(e.get("category"))
                        .append(" source_type=").append(e.get("source_type"))
                        .append(" source_event_id=").append(e.get("source_event_id"))
                        .append(" occurred_at=").append(e.get("occurred_at"));
            }

            Long extEntriesAll = jdbc.queryForObject(
                    "SELECT count(*) FROM timeline_entry WHERE source_type = 'ext_interaction'",
                    Long.class);
            sb.append("\n  [诊断] ext_interaction 投影条目全库计数=").append(extEntriesAll)
                    .append("（台账有行而此值=0 ⇒ 投影环节未产出；此值>0 而上面 request_id 列表里没有"
                            + " ⇒ 条目被投影到了别的 request_id 或已被 deleteBySourceRange 删除）");

            String contractId = subject.substring(subject.indexOf(':') + 1);
            Long subjectLinks = jdbc.queryForObject(
                    "SELECT count(*) FROM timeline_entry_subject WHERE subject_type = 'contract'"
                            + " AND subject_id = ?", Long.class, contractId);
            sb.append("\n  [诊断] timeline_entry_subject（contract/").append(contractId)
                    .append(" 主体链接）=").append(subjectLinks)
                    .append("（条目存在而链接缺失 ⇒ insertSubject 分支未走到，"
                            + "见 DefaultTimelineProjector:79-86 需 subjectType 与 subjectId 同时非空）");
            return sb.toString();
        } catch (RuntimeException e) {
            return "\n  [诊断] 取证查询自身失败（不影响上述断言结论）: " + e;
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
