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
            // 与下面第 4/5 步同因：必须分页遍历，不能只读首页。本 Turn 自身就会产生两百余条
            // run_manifest / model_usage（一个 turn 的每轮 LLM 调用各一条），总数在 200 阈值附近波动，
            // 越过即因 ORDER BY occurred_at ASC LIMIT 200 截掉尾部——而 TOOL 条目的 occurred_at
            // 往往晚于边界，于是表现为「裸 SQL（无 limit）查得到、pageByRequest 查不到」的
            // ④-a 偶发红。分页后该现象消除。
            Set<TimelineCategory> writeTimeCats = principals.runAs(user,
                    () -> collectAllCategoriesByRequest(timeline, requestId, spec));
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
            // 必须遍历全部分页：TimelinePageSpec.MAX_LIMIT=200 且构造器强制 Math.min(limit, MAX_LIMIT)，
            // 单页取不全。**本 Turn 自身**产生的条目数即可超过 200（2026-11-24 实测同一 request_id
            // 达 273 条：run_manifest 122 + model_usage 137 + runtime_item 10 + ext_interaction 3 +
            // runtime_approval 1——three-tools 剧本每轮 LLM 调用各产出一条 run_manifest 与 model_usage），
            // 只读首页会截掉尾部；而 ext_interaction 的三条恰是 occurred_at 最晚的，SQL 又是
            // ORDER BY occurred_at ASC LIMIT，故升序首页必然先丢这三条。
            // 这正是本闸门 ④-a / ④-b 偶发红的共同根因（由 diagnoseRequestChain 取证确诊）。
            Set<TimelineCategory> requestCats = principals.runAs(user,
                    () -> collectAllCategoriesByRequest(timeline, requestId, spec));
            assertTrue(requestCats.containsAll(Set.of(TimelineCategory.RUN, TimelineCategory.MODEL,
                            TimelineCategory.TOOL, TimelineCategory.INBOUND, TimelineCategory.OUTBOUND,
                            TimelineCategory.NOTIFY)),
                    () -> "请求时间线应覆盖合同全链，实际=" + requestCats
                            + diagnoseRequestChain(jdbc, requestId, subject, from, to, spec));

            // —— 5. 断言①（续）：合同主体时间线「从推送到通知在一条时间线」——
            Set<TimelineCategory> subjectCats = principals.runAs(user,
                    () -> collectAllCategoriesBySubject(timeline, "contract", contractId, spec));
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

    /** 分页遍历上限：防 cursor 精度问题导致死循环（200/页 × 50 = 1 万条，远超实际规模）。 */
    private static final int MAX_PAGES = 50;

    /**
     * 按游标分页遍历「请求视角」时间线，收集全部类别（ADR-075 §4 游标分页口径）。
     *
     * <p>为什么必须分页而不能单页取全：{@code TimelinePageSpec} 构造器强制
     * {@code limit = Math.min(limit, MAX_LIMIT)} 且 {@code MAX_LIMIT = 200}，limit 无法调大；
     * 而<b>本 Turn 自身</b>产生的条目数即可超过 200（2026-11-24 实测同一 {@code request_id}
     * 达 273 条：{@code three-tools} 剧本每轮 LLM 调用各产出一条 run_manifest 与 model_usage），
     * 单页必然截断。SQL 是 {@code ORDER BY occurred_at ASC LIMIT n}，
     * 被截掉的恰是<b>最晚</b>的条目——本用例的 INBOUND/OUTBOUND/NOTIFY 由台账在测试末段写入，
     * {@code occurred_at} 正是全链最晚的三条，故最先被截掉。这是 ④-a / ④-b 偶发红的共同根因。
     *
     * <p>终止条件取三者之一（缺一不可）：① 本页行数 < limit（正常末页）；
     * ② 游标未推进——{@code occurred_at} 是微秒精度而 cursor 只到毫秒（{@code epochMilli:id}），
     * 截断会使末页最后一条被 {@code occurred_at > cursorTs} 重复取回，若末页仅 1 条则游标原地不动，
     * 不检测即死循环；③ 达到 {@link #MAX_PAGES} 硬上限。
     * 类别收进 {@link Set}，重复条目天然去重，不影响断言结果。
     */
    private static Set<TimelineCategory> collectAllCategoriesByRequest(TimelineQuery timeline,
                                                                       String requestId,
                                                                       TimelinePageSpec firstPage) {
        Set<TimelineCategory> categories = new java.util.LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            TimelinePageSpec spec = withCursor(firstPage, cursor);
            List<TimelineEntryView> rows = timeline.pageByRequest(requestId, spec);
            rows.forEach(row -> categories.add(row.category()));
            if (rows.size() < firstPage.limit()) {
                return categories;
            }
            String next = cursorOf(rows.get(rows.size() - 1));
            if (next.equals(cursor)) {
                return categories;
            }
            cursor = next;
        }
        return categories;
    }

    /** 同 {@link #collectAllCategoriesByRequest}，但走「合同主体视角」。 */
    private static Set<TimelineCategory> collectAllCategoriesBySubject(TimelineQuery timeline,
                                                                       String subjectType,
                                                                       String subjectId,
                                                                       TimelinePageSpec firstPage) {
        Set<TimelineCategory> categories = new java.util.LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            TimelinePageSpec spec = withCursor(firstPage, cursor);
            List<TimelineEntryView> rows = timeline.pageBySubject(subjectType, subjectId, spec);
            rows.forEach(row -> categories.add(row.category()));
            if (rows.size() < firstPage.limit()) {
                return categories;
            }
            String next = cursorOf(rows.get(rows.size() - 1));
            if (next.equals(cursor)) {
                return categories;
            }
            cursor = next;
        }
        return categories;
    }

    /** 替换 spec 的游标，其余条件（类别/时间窗/limit/clearance/空间）保持不变。 */
    private static TimelinePageSpec withCursor(TimelinePageSpec base, String cursor) {
        return new TimelinePageSpec(base.categories(), base.from(), base.to(), cursor,
                base.limit(), base.clearance(), base.workspaceId());
    }

    /** 按 {@code TimelinePageSpec} javadoc 约定的 {@code epochMilli:id} 构造下一页游标。 */
    private static String cursorOf(TimelineEntryView last) {
        return last.occurredAt().toEpochMilli() + ":" + last.id();
    }

    /**
     * ④ 偶发红的取证辅助（2026-11-24 新增，同日确诊根因后保留作回归防线）。
     *
     * <p><b>根因（已确诊，非推测）</b>：断言「时间线覆盖合同全链」原先只读 {@code pageByRequest}
     * 的<b>首页</b>，而 {@code TimelinePageSpec} 强制 {@code limit ≤ MAX_LIMIT = 200}，SQL 为
     * {@code ORDER BY occurred_at ASC LIMIT n}。gate 共享同一 PG 容器，同一 {@code request_id}
     * 下的条目随本 Turn 的 LLM 轮次累积（实测 273 条：run_manifest 122 + model_usage 137 +
     * runtime_item 10 + ext_interaction 3 + runtime_approval 1），越过 200 后<b>尾部被截断</b>；
     * 而 INBOUND/OUTBOUND/NOTIFY 三条由台账在测试末段写入、{@code occurred_at} 最晚，故最先被截掉。
     * 这是 ④-a（`:102` 缺 TOOL）与 ④-b（`:136` 缺 INBOUND/OUTBOUND/NOTIFY）<b>共同的根因</b>，
     * 也解释了上批记录的怪象「裸 SQL 看得到、pageByRequest 读不到」——裸 SQL 无 limit。
     * 决定性证据：273 个 {@code source_event_id} 全部唯一（幂等键正常、无重复投影），
     * 且 3 条 ext_interaction 的 request_id 与查询完全匹配（投影正确，只是被分页截掉）。
     *
     * <p>修法见 {@link #collectAllCategoriesByRequest}：按产品既定的游标分页口径遍历全部页。
     * <b>本诊断保留</b>：分页修法若被回归破坏，或出现新的失效模式（台账未落库 / 投影到错误
     * request_id / 被 {@code deleteBySourceRange} 误删），失败消息仍会给出下列事实：
     * <ol>
     *   <li>rebuild 窗口与台账行的 {@code ts}（比对是否落在窗口内）；</li>
     *   <li>库内该 request_id 的<b>总行数 vs 单页 limit</b>（分页截断的直接指标）；</li>
     *   <li>按 category/source_type 的聚合计数（缺哪类、由哪个来源产出）；</li>
     *   <li>{@code ext_interaction} 投影全库计数与 contract 主体链接计数。</li>
     * </ol>
     *
     * <p>设计约束（勿改回带时间参数的查询）：
     * <ul>
     *   <li><b>不向 SQL 传 {@code Instant}/时间参数</b>。全仓无 {@code JdbcTemplate} 直传
     *       {@code Instant} 的先例（生产 {@code AgentSpecBackfill:105} 用的是
     *       {@code PreparedStatement.setTimestamp}），而 TIMESTAMPTZ ↔ Java 时间类型的桥接
     *       正是本项目已踩过的坑；诊断代码若因参数绑定失败而抛异常，恰好在最需要它时失效。
     *       故改为打印实际值 + 窗口 ISO-8601 字串，由读日志者直接比对。</li>
     *   <li>只在断言失败时由 JUnit 的 {@code Supplier} 惰性调用，正常路径零开销。</li>
     *   <li>整体 try/catch，<b>诊断自身抛异常不得掩盖原始断言失败</b>。</li>
     * </ul>
     */
    private static String diagnoseRequestChain(JdbcTemplate jdbc, String requestId, String subject,
                                               Instant from, Instant to, TimelinePageSpec spec) {
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
            // 逐行 dump 会产出数百行噪音（实测单 request_id 曾达 273 条），故改为按
            // category × source_type 聚合计数，并直接给出「库内总行数 vs 断言所用单页 limit」——
            // 后者正是分页截断的直接指标：总数 > limit 即说明单页取不全，与断言实际看到的类别集合对照即可定位。
            java.util.Map<String, Integer> byCategory = new java.util.TreeMap<>();
            for (Map<String, Object> e : entries) {
                String key = e.get("category") + "/" + e.get("source_type");
                byCategory.merge(key, 1, Integer::sum);
            }
            sb.append("\n  [诊断] timeline_entry（request_id=").append(requestId)
                    .append("）库内共 ").append(entries.size())
                    .append(" 行；断言所用单页 limit=").append(limitOf(spec))
                    .append(entries.size() > limitOf(spec)
                            ? " ⇒ 【总数已超过单页 limit，若断言只读首页则尾部类别会被 ASC 截断】"
                            : "（总数未超 limit，排除分页截断，另有他因）")
                    .append("\n      按 category/source_type 聚合：").append(byCategory);

            Long extEntriesAll = jdbc.queryForObject(
                    "SELECT count(*) FROM timeline_entry WHERE source_type = 'ext_interaction'",
                    Long.class);
            sb.append("\n  [诊断] ext_interaction 投影条目全库计数=").append(extEntriesAll)
                    .append("（台账有行而此值=0 ⇒ 投影环节未产出；此值>0 而上面聚合里没有 ext_interaction"
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

    /** 断言所用分页 spec 的每页条数（诊断报告用，用于与库内总行数对照判断是否被截断）。 */
    private static int limitOf(TimelinePageSpec spec) {
        return spec == null ? TimelinePageSpec.DEFAULT_LIMIT : spec.limit();
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
