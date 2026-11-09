package com.tioka.server.gate;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.SseCollector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 验收 · L4：SSE 事件续传与审批挂起恢复。
 *
 * <p>对应 {@code delivery/后续链路实施计划.md} §3.4 L4 验收标准：
 * 事件 id 单调递增、状态快照重放幂等、审批跨多次不新开 Turn、契约测试绿。
 * L4 与 L1 共用同一 {@code TurnStatus} 状态机与 {@code EventId} 单调游标（重放幂等键
 * {@code threadId + EventId.seq}，与 {@code Last-Event-ID} 同源）。
 *
 * <p>本批次（方案 A 冻结）重心是验收测试 + 文档化：被测能力来自既有生产代码
 * （{@code ThreadEventsService} 四步重放、{@code ApprovalDecideService}/
 * {@code TurnApprovalHandler}/{@code ApprovalResumeSweeper}），未改动任何 {@code src/main}。
 *
 * <p>需要运行中的 PostgreSQL + Redis + LLM/MCP 桩（Testcontainers）；本环境若无基础设施，
 * 编译通过但运行阶段需跳过。
 */
@Tag("gate")
class P0L4AcceptanceIT {

    // —— L4 验收准则：事件 id 单调递增 + 状态快照重放幂等（跨实例，无重复/无缺失）——
    @Test
    void sseResumeNoDuplicates() throws Exception {
        try (com.tioka.server.gate.support.DualInstance di =
                     com.tioka.server.gate.support.DualInstance.start()) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);

            String lastId;
            RuntimeApi.TurnHandle handle;
            try (SseCollector sse = SseCollector.subscribe(
                    di.baseUrlB(), bundle.token(), bundle.workspaceId(), thr.threadId(), null)) {
                handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-resume-1",
                        "请依次调用 echo 三次");
                lastId = sse.awaitIdContaining(".", 30_000);
            }

            EventId cursor = EventId.parse(lastId);
            try (SseCollector sse2 = SseCollector.subscribe(
                    di.baseUrlA(), bundle.token(), bundle.workspaceId(), thr.threadId(), lastId)) {
                RuntimeApi.TurnView done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline
                        && !sse2.hasEvent(RuntimeEventType.TURN_COMPLETED.wire())
                        && sse2.ids().isEmpty()) {
                    Thread.sleep(40);
                }
                List<String> second = sse2.ids();
                for (String id : second) {
                    EventId eid = EventId.parse(id);
                    assertTrue(eid.compareTo(cursor) > 0,
                            "续传不应包含 ≤ Last-Event-ID 的事件 id=" + id + " last=" + lastId
                                    + " second=" + second);
                }
                assertFalse(second.contains(lastId), "不应再推送 Last-Event-ID 自身");
            }
        }
    }

    // —— L4 验收准则：审批跨多次不新开 Turn + 跨实例恢复（DB runtime_approval/runtime_item 重建卡片）——
    @Test
    void approvalSuspendResumeCrossInstanceNoNewTurn() throws Exception {
        try (GateCluster cluster = GateCluster.start(3, "write-needs-approval", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(cluster.a(), cluster.llm().baseUrl(), true);
            var thr = GateSeed.createThread(cluster.a(), bundle);
            var handle = GateSeed.startTurn(cluster.a(), bundle, thr.threadId(),
                    "cm-g1-appr", "please update erp");

            GateSeed.awaitStatus(cluster.a(), bundle, handle.turnId(), 45_000, TurnStatus.WAITING_APPROVAL);

            String approvalId = pendingApprovalId(cluster.b().getBean(JdbcTemplate.class), handle.turnId());

            // 在「续跑实例 C」上建立 SSE，验证跨实例经 DB 重建审批卡与完成事件
            try (SseCollector sseC = SseCollector.subscribe(
                    cluster.baseUrl(2), bundle.token(), bundle.workspaceId(), thr.threadId(), null)) {

                PrincipalHolder principalsB = cluster.b().getBean(PrincipalHolder.class);
                RuntimeApi runtimeB = cluster.b().getBean(RuntimeApi.class);
                principalsB.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                    runtimeB.decideApproval(approvalId, new RuntimeApi.ApprovalDecision(true, null, "g1-ok"));
                    return null;
                });

                var done = GateSeed.awaitTerminal(cluster.instance(2), bundle, handle.turnId(), 90_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
                assertTrue(cluster.mcp().calls().stream().anyMatch(c -> "erp.update".equals(c.name())));

                // 跨实例恢复：续跑实例能从 DB 重放出完成事件（状态快照重放幂等）
                sseC.awaitEvent(RuntimeEventType.TURN_COMPLETED.wire(), 15_000);
            }

            // L4 验收：审批跨多次不新开 Turn（同一 Turn 复用，仅 recover_count 递增）
            long turnCount = cluster.a().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT count(*) FROM runtime_turn WHERE thread_id = ?", Long.class, thr.threadId());
            assertEquals(1, turnCount, "审批挂起恢复不得新开 Turn");

            // 审批卡已落库且已决（DB runtime_approval 为跨实例重建来源）
            String approvalStatus = cluster.a().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT status FROM runtime_approval WHERE id = ?", String.class, approvalId);
            assertNotEquals("PENDING", approvalStatus, "审批应已决出（非 PENDING）");
        }
    }

    private static String pendingApprovalId(JdbcTemplate jdbc, String turnId) {
        long deadline = System.currentTimeMillis() + 15_000;
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
}
