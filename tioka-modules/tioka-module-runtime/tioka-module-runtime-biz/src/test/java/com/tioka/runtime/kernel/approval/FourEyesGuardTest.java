package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TRU-02 四眼审批服务端二次校验离线测试（M3-05 §3.4）：fourEyes=true 的审批，
 * 决定人不能是工单请求人（requested_by）或 Turn 发起人（run_as）；
 * 违规 FORBIDDEN 拒绝并写 FOUR_EYES_SELF_APPROVAL 审计（绕过前端过滤也无效）。
 */
class FourEyesGuardTest {

    private ApprovalRepository approvals;
    private TurnRepository turns;
    private PrincipalHolder principals;
    private AuditApi audit;
    private ApprovalDecideService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        approvals = mock(ApprovalRepository.class);
        turns = mock(TurnRepository.class);
        principals = mock(PrincipalHolder.class);
        audit = mock(AuditApi.class);
        ObjectProvider<AuditApi> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        ObjectProvider<AgentSnapshotApi> snapshots = mock(ObjectProvider.class);
        when(snapshots.getIfAvailable()).thenReturn(null);
        service = new ApprovalDecideService(approvals, turns, mock(RuntimeThreadRepository.class),
                principals, mock(ToolRegistry.class), mock(EventStream.class),
                mock(RuntimeEventFactory.class), new ObjectMapper(), auditProvider, snapshots,
                mock(com.tioka.framework.messaging.EventPublisher.class),
                mock(RuntimeApprovalRouterConsumer.class), mock(TransactionTemplate.class));
    }

    private static Principal approver(String userId) {
        return new Principal(userId, "ws1", PrincipalType.USER, Set.of(), Set.of("*"), "password");
    }

    /** fourEyes 标记在 card_json（第 9 步 ESCALATE 写入）。 */
    private static RuntimeApprovalDO approval(String requestedBy, String turnId) {
        RuntimeApprovalDO a = new RuntimeApprovalDO();
        a.setId("apr1");
        a.setWorkspaceId("ws1");
        a.setSubjectType("turn");
        a.setSubjectId(turnId);
        a.setKind("tool");
        a.setCardJson("{\"fourEyes\":true}");
        a.setStatus("PENDING");
        a.setRequestedBy(requestedBy);
        return a;
    }

    private static RuntimeTurnDO turn(String runAsUserId) {
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId("turn1");
        t.setThreadId("thr1");
        t.setRunAsJson("{\"userId\":\"" + runAsUserId + "\"}");
        return t;
    }

    @Test
    void requesterCannotDecideFourEyesApproval() {
        when(approvals.findForUpdate("apr1")).thenReturn(Optional.of(approval("1", "turn1")));
        when(turns.findById("turn1")).thenReturn(Optional.of(turn("9")));
        when(principals.current()).thenReturn(approver("1"));

        BizException e = assertThrows(BizException.class, () ->
                service.decide("apr1", new RuntimeApi.ApprovalDecision(true, null, "ok")));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        assertEquals("四眼审批：发起人与工单请求人不能审批", e.getMessage());

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).recordIndependent(ev.capture());
        assertEquals(AuditOutcome.DENIED, ev.getValue().outcome());
        assertEquals("FOUR_EYES_SELF_APPROVAL", ev.getValue().reason());
    }

    @Test
    void turnInitiatorCannotDecideFourEyesApproval() {
        // requested_by 为空（历史口径），但 Turn 发起人（run_as）= 审批人
        when(approvals.findForUpdate("apr1")).thenReturn(Optional.of(approval(null, "turn1")));
        when(turns.findById("turn1")).thenReturn(Optional.of(turn("1")));
        when(principals.current()).thenReturn(approver("1"));

        BizException e = assertThrows(BizException.class, () ->
                service.decide("apr1", new RuntimeApi.ApprovalDecision(false, Map.of(), null)));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(audit).recordIndependent(any());
    }

    @Test
    void otherApproverPassesFourEyesGuard() {
        // 审批人既非请求人也非发起人 → 通过四眼校验（进入后续决定流程，此处只断言不抛四眼异常）
        when(approvals.findForUpdate("apr1")).thenReturn(Optional.of(approval("1", "turn1")));
        when(approvals.findById("apr1")).thenReturn(Optional.of(approval("1", "turn1")));
        when(turns.findById("turn1")).thenReturn(Optional.of(turn("9")));
        when(principals.current()).thenReturn(approver("7"));
        try {
            service.decide("apr1", new RuntimeApi.ApprovalDecision(true, null, "ok"));
        } catch (BizException e) {
            assertEquals(false, e.getMessage().contains("四眼审批"),
                    "合规审批人不应被四眼校验拦截: " + e.getMessage());
        }
    }

    @Test
    void fourEyesWithoutExplicitAssigneeAllowsThirdParty() {
        // 授权书 ESCALATE 生成的审批：assignee_json 只有 fourEyes 标记、userIds/roleCodes 为空。
        // 此时不能按「空 = 仅发起人」解释，否则四眼禁止发起人会形成无人可批的死锁。
        when(approvals.findForUpdate("apr1")).thenReturn(Optional.of(approval("1", "turn1")));
        when(approvals.findById("apr1")).thenReturn(Optional.of(approval("1", "turn1")));
        when(turns.findById("turn1")).thenReturn(Optional.of(turn("9")));
        when(principals.current()).thenReturn(approver("7"));
        try {
            service.decide("apr1", new RuntimeApi.ApprovalDecision(true, null, "ok"));
        } catch (BizException e) {
            assertFalse(e.getMessage().contains("非审批人"),
                    "四眼审批未指定审批人时，第三人不得被 assignee 校验拦下: " + e.getMessage());
        }
    }

    @Test
    void nonFourEyesApprovalNotSubjectToGuard() {
        RuntimeApprovalDO plain = approval("1", "turn1");
        plain.setCardJson("{}");
        when(approvals.findForUpdate("apr1")).thenReturn(Optional.of(plain));
        when(approvals.findById("apr1")).thenReturn(Optional.of(plain));
        when(turns.findById("turn1")).thenReturn(Optional.of(turn("1")));
        when(principals.current()).thenReturn(approver("1"));
        try {
            service.decide("apr1", new RuntimeApi.ApprovalDecision(true, null, "ok"));
        } catch (BizException e) {
            assertEquals(false, e.getMessage().contains("四眼审批"),
                    "普通审批不受四眼约束: " + e.getMessage());
        }
    }
}
