package com.tioka.server.runtime;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-07：审批挂起与续跑（M1-02）。
 */
@Tag("it")
class ApprovalFlowIT {

    @Test
    void approveResumes() throws Exception {
        try (DualInstance di = DualInstance.start("write-needs-approval", Map.of())) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-appr-ok", "please update");

            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            String approvalId = pendingApprovalId(di.a().getBean(JdbcTemplate.class), handle.turnId());

            principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                runtime(di).decideApproval(approvalId, new RuntimeApi.ApprovalDecision(true, null, "ok"));
                return null;
            });

            var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
            assertTrue(di.mcp().calls().stream().anyMatch(c -> "erp.update".equals(c.name())));
        }
    }

    @Test
    void rejectFeedsModel() throws Exception {
        try (DualInstance di = DualInstance.start("reject-then-answer", Map.of())) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-appr-rej", "please update");

            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            String approvalId = pendingApprovalId(di.a().getBean(JdbcTemplate.class), handle.turnId());

            principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                runtime(di).decideApproval(approvalId,
                        new RuntimeApi.ApprovalDecision(false, null, "nope"));
                return null;
            });

            var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status());
            assertTrue(di.mcp().calls().isEmpty(), "拒绝后不应调用 MCP");
            List<RuntimeItemDO> results = di.a().getBean(ItemRepository.class)
                    .listByThread(thr.threadId()).stream()
                    .filter(i -> ItemType.TOOL_RESULT.wire().equals(i.getType()))
                    .toList();
            assertFalse(results.isEmpty());
            assertTrue(results.get(0).getContentJson().contains("用户拒绝执行"));
        }
    }

    @Test
    void mixedStep() throws Exception {
        try (DualInstance di = DualInstance.start("mixed-read-write", Map.of())) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-appr-mix", "mixed");

            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            assertTrue(di.mcp().calls().stream().anyMatch(c -> "erp.query".equals(c.name())),
                    "READ 应立即执行");
            assertTrue(di.mcp().calls().stream().noneMatch(c -> "erp.update".equals(c.name())),
                    "WRITE 应等待审批");

            String approvalId = pendingApprovalId(di.a().getBean(JdbcTemplate.class), handle.turnId());
            principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                runtime(di).decideApproval(approvalId, new RuntimeApi.ApprovalDecision(true, null, null));
                return null;
            });
            GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertTrue(di.mcp().calls().stream().anyMatch(c -> "erp.update".equals(c.name())));
        }
    }

    @Test
    void editedArgsValidated() throws Exception {
        try (DualInstance di = DualInstance.start("write-needs-approval", Map.of())) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-appr-edit", "update");

            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            String approvalId = pendingApprovalId(di.a().getBean(JdbcTemplate.class), handle.turnId());

            BizException bad = assertThrows(BizException.class, () ->
                    principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                        runtime(di).decideApproval(approvalId, new RuntimeApi.ApprovalDecision(
                                true, Map.of("args", Map.of("q", 123)), null));
                        return null;
                    }));
            assertEquals(ErrorCode.BAD_REQUEST, bad.getErrorCode());

            principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                runtime(di).decideApproval(approvalId, new RuntimeApi.ApprovalDecision(
                        true, Map.of("args", Map.of("q", "ok-new")), null));
                return null;
            });
            GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertTrue(di.mcp().calls().stream()
                    .anyMatch(c -> "erp.update".equals(c.name())
                            && "ok-new".equals(String.valueOf(c.arguments().get("q")))));
        }
    }

    @Test
    void nonAssignee403() throws Exception {
        try (DualInstance di = DualInstance.start("write-needs-approval", Map.of())) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-appr-403", "update");
            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            String approvalId = pendingApprovalId(di.a().getBean(JdbcTemplate.class), handle.turnId());

            Principal stranger = new Principal("999999", bundle.workspaceId(), PrincipalType.USER,
                    Set.of("member"), Set.of("approval:decide", "chat:use"), "password");
            BizException ex = assertThrows(BizException.class, () ->
                    principals(di).runAs(stranger, () -> {
                        runtime(di).decideApproval(approvalId,
                                new RuntimeApi.ApprovalDecision(true, null, null));
                        return null;
                    }));
            assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        }
    }

    @Test
    void doubleDecideIdempotent() throws Exception {
        try (DualInstance di = DualInstance.start("write-needs-approval", Map.of())) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-appr-idem", "update");
            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            String approvalId = pendingApprovalId(di.a().getBean(JdbcTemplate.class), handle.turnId());

            principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                runtime(di).decideApproval(approvalId, new RuntimeApi.ApprovalDecision(true, null, null));
                runtime(di).decideApproval(approvalId, new RuntimeApi.ApprovalDecision(true, null, null));
                return null;
            });
            GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);

            BizException conflict = assertThrows(BizException.class, () ->
                    principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                        runtime(di).decideApproval(approvalId,
                                new RuntimeApi.ApprovalDecision(false, null, "late"));
                        return null;
                    }));
            assertEquals(ErrorCode.CONFLICT, conflict.getErrorCode());
        }
    }

    @Test
    void waitingDoesNotHoldConcurrency() throws Exception {
        // always-write-approval：每次模型调用都要审批；maxConcurrentTurns=1 时第二 Turn 仍能挂起，证明槽已释放
        try (DualInstance di = DualInstance.start("always-write-approval", Map.of(
                "tioka.runtime.executor.max-concurrent-turns", "1"))) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            var thr1 = GateSeed.createThread(di.a(), bundle);
            var h1 = GateSeed.startTurn(di.a(), bundle, thr1.threadId(), "cm-conc-1", "update");
            GateSeed.awaitStatus(di.a(), bundle, h1.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);

            var thr2 = GateSeed.createThread(di.a(), bundle);
            var h2 = GateSeed.startTurn(di.a(), bundle, thr2.threadId(), "cm-conc-2", "update again");
            GateSeed.awaitStatus(di.a(), bundle, h2.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);

            // 仅断言并发槽释放；不要求续跑到 COMPLETED（loop 工具剧本会再次挂起）
            assertEquals(TurnStatus.WAITING_APPROVAL,
                    principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () ->
                            runtime(di).getTurn(h1.turnId())).status());
            assertEquals(TurnStatus.WAITING_APPROVAL,
                    principals(di).runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () ->
                            runtime(di).getTurn(h2.turnId())).status());
        }
    }

    private static String pendingApprovalId(JdbcTemplate jdbc, String turnId) {
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

    private static PrincipalHolder principals(DualInstance di) {
        return di.a().getBean(PrincipalHolder.class);
    }

    private static RuntimeApi runtime(DualInstance di) {
        return di.a().getBean(RuntimeApi.class);
    }
}
