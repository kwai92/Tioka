package com.tioka.runtime.application.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 撤销鉴权矩阵（M3-05 §5.1 五类可撤销人）。M3-05 §5.1 的飞书卡片「撤销本次」按钮
 * HMAC 签名链路（CON-03）在当前仓库无推送出口，按缺口口径简化为 REST 出口 +
 * 审批台徽标先行（登记 M3-C4）；签名错/非可撤销人被拒在 REST 侧对应：
 * 非可撤销人 → 403。跨实例与真实容器验证归 CI。
 */
class UndoCardIT {

    private static final String TURN = "turn_a1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final TurnRepository turns = mock(TurnRepository.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final com.tioka.agent.api.AgentSnapshotApi snapshots =
            mock(com.tioka.agent.api.AgentSnapshotApi.class);

    private UndoAuthorizer authorizer() {
        return new UndoAuthorizer(turns, approvals,
                new UndoCardIT.SnapshotProvider(snapshots), Optional.empty(), mapper);
    }

    /** UndoAuthorizer 依赖 ObjectProvider；测试用固定提供者。 */
    static final class SnapshotProvider implements org.springframework.beans.factory.ObjectProvider<com.tioka.agent.api.AgentSnapshotApi> {
        private final com.tioka.agent.api.AgentSnapshotApi value;

        SnapshotProvider(com.tioka.agent.api.AgentSnapshotApi value) {
            this.value = value;
        }

        @Override
        public com.tioka.agent.api.AgentSnapshotApi getObject() {
            return value;
        }

        @Override
        public com.tioka.agent.api.AgentSnapshotApi getObject(Object... args) {
            return value;
        }

        @Override
        public com.tioka.agent.api.AgentSnapshotApi getIfAvailable() {
            return value;
        }

        @Override
        public com.tioka.agent.api.AgentSnapshotApi getIfUnique() {
            return value;
        }
    }

    private static Principal user(String userId, Set<String> permissions) {
        return new Principal(userId, "ws_1", PrincipalType.USER, Set.of(), permissions, "password");
    }

    private com.tioka.runtime.infrastructure.persistence.TrustActionDO action(String approvalId) {
        com.tioka.runtime.infrastructure.persistence.TrustActionDO row =
                new com.tioka.runtime.infrastructure.persistence.TrustActionDO();
        row.setId("act_1");
        row.setWorkspaceId("ws_1");
        row.setAgentId("agt_1");
        row.setAgentVersionId("av_1");
        row.setTurnId(TURN);
        row.setApprovalId(approvalId);
        return row;
    }

    @Test
    void turnInitiatorAllowed() {
        RuntimeTurnDO turn = new RuntimeTurnDO();
        turn.setId(TURN);
        turn.setRunAsJson("{\"userId\":\"user_9\",\"workspaceId\":\"ws_1\"}");
        when(turns.findById(TURN)).thenReturn(Optional.of(turn));
        assertDoesNotThrow(() -> authorizer().check(user("user_9", Set.of()), TURN, List.of(action(null))));
    }

    @Test
    void agentOwnerAllowed() {
        when(turns.findById(anyString())).thenReturn(Optional.empty());
        com.tioka.agent.api.AgentSnapshotApi.AgentSnapshot snap =
                new com.tioka.agent.api.AgentSnapshotApi.AgentSnapshot(
                        "av_1", "agt_1", 1, "ws_1", "owner_7", "hash", null, null);
        when(snapshots.currentPublished("agt_1")).thenReturn(snap);
        assertDoesNotThrow(() -> authorizer().check(user("owner_7", Set.of()), TURN, List.of(action(null))));
    }

    @Test
    void approverAllowed() {
        when(turns.findById(anyString())).thenReturn(Optional.empty());
        RuntimeApprovalDO approval = new RuntimeApprovalDO();
        approval.setId("apv_1");
        approval.setDecidedBy("approver_3");
        when(approvals.findById("apv_1")).thenReturn(Optional.of(approval));
        assertDoesNotThrow(() -> authorizer().check(user("approver_3", Set.of()), TURN,
                List.of(action("apv_1"))));
    }

    @Test
    void workspaceAdminAllowed() {
        when(turns.findById(anyString())).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> authorizer().check(
                user("admin_1", Set.of("workspace:manage")), TURN, List.of(action(null))));
    }

    @Test
    void unrelatedUserRejected() {
        // 签名错/非可撤销人被拒（卡片链路简化为 REST 鉴权）：非可撤销人 → 403
        when(turns.findById(anyString())).thenReturn(Optional.empty());
        when(approvals.findById(anyString())).thenReturn(Optional.empty());
        BizException e = assertThrows(BizException.class,
                () -> authorizer().check(user("stranger_5", Set.of()), TURN, List.of(action(null))));
        assertSame(ErrorCode.FORBIDDEN, e.getErrorCode());
    }
}
