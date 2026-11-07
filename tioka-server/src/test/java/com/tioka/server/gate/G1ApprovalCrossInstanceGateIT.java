package com.tioka.server.gate;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G1：RT-07。A 挂起、B 审批、C（或任一实例）续跑至 COMPLETED。
 */
@Tag("gate")
class G1ApprovalCrossInstanceGateIT {

    @Test
    void suspendOnAApproveOnBResumeOnC() throws Exception {
        try (GateCluster cluster = GateCluster.start(3, "write-needs-approval", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(cluster.a(), cluster.llm().baseUrl(), true);
            var thr = GateSeed.createThread(cluster.a(), bundle);
            var handle = GateSeed.startTurn(cluster.a(), bundle, thr.threadId(),
                    "cm-g1-appr", "please update erp");

            GateSeed.awaitStatus(cluster.a(), bundle, handle.turnId(), 45_000, TurnStatus.WAITING_APPROVAL);

            String approvalId = pendingApprovalId(cluster.b().getBean(JdbcTemplate.class), handle.turnId());
            PrincipalHolder principalsB = cluster.b().getBean(PrincipalHolder.class);
            RuntimeApi runtimeB = cluster.b().getBean(RuntimeApi.class);
            principalsB.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                runtimeB.decideApproval(approvalId, new RuntimeApi.ApprovalDecision(true, null, "g1-ok"));
                return null;
            });

            // 续跑可由 C（或任一）执行
            var done = GateSeed.awaitTerminal(cluster.instance(2), bundle, handle.turnId(), 90_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
            assertTrue(cluster.mcp().calls().stream().anyMatch(c -> "erp.update".equals(c.name())));
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
