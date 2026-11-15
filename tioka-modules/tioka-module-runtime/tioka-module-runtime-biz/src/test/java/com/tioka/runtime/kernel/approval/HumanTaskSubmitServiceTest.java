package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.infrastructure.persistence.RuntimeHumanTaskDO;
import com.tioka.runtime.kernel.infrastructure.HumanTaskRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCH-09 / ADR-066 §7.4：人工补录提交服务离线测试（权限校验、幂等、状态机、重新入队）。
 *
 * <p>本服务只负责置任务 {@code SUBMITTED} 并把原 {@code WAITING_APPROVAL} Turn 重新入队；
 * 人提交数据的第 3〜8 步校验与 data item 写入由续跑后的
 * {@code OutputStage.resumePending} 承接（端到端见 {@code OutputStageIT}）。
 */
class HumanTaskSubmitServiceTest {

    private HumanTaskRepository humanTasks;
    private TurnRepository turns;
    private TurnDispatchQueue dispatchQueue;
    private PrincipalHolder principals;
    private HumanTaskSubmitService service;

    @BeforeEach
    void setUp() {
        humanTasks = mock(HumanTaskRepository.class);
        turns = mock(TurnRepository.class);
        dispatchQueue = mock(TurnDispatchQueue.class);
        principals = mock(PrincipalHolder.class);
        service = new HumanTaskSubmitService(humanTasks, turns, dispatchQueue, principals, new ObjectMapper());
    }

    private static Principal approver() {
        return new Principal("u-7", "ws1", PrincipalType.USER, Set.of(), Set.of("*"), "password");
    }

    private static RuntimeHumanTaskDO task(String status) {
        RuntimeHumanTaskDO t = new RuntimeHumanTaskDO();
        t.setId("htk1");
        t.setSubjectType("turn");
        t.setSubjectId("turn1");
        t.setTurnId("turn1");
        t.setThreadId("thr1");
        t.setStatus(status);
        return t;
    }

    @Test
    void submitMarksSubmittedAndRequeuesTurn() {
        when(principals.current()).thenReturn(approver());
        when(humanTasks.findForUpdate("htk1")).thenReturn(Optional.of(task("PENDING")));
        when(humanTasks.submit(eq("htk1"), eq("u-7"), any())).thenReturn(1);
        when(turns.requeueFromWaitingApproval("turn1")).thenReturn(1);

        service.submit("htk1", Map.of("conclusion", "SATISFIED"));

        verify(humanTasks).submit(eq("htk1"), eq("u-7"), any());
        verify(turns).requeueFromWaitingApproval("turn1");
        // 无事务同步时 afterCommit 立即执行 → 直接入队
        verify(dispatchQueue).offer("turn1", "thr1");
    }

    @Test
    void submitRequiresApprovalDecidePermission() {
        Principal noPerm = new Principal("u-7", "ws1", PrincipalType.USER, Set.of(), Set.of(), "password");
        when(principals.current()).thenReturn(noPerm);

        BizException e = assertThrows(BizException.class, () -> service.submit("htk1", Map.of()));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(humanTasks, never()).submit(any(), any(), any());
    }

    @Test
    void submitIsIdempotentWhenAlreadySubmitted() {
        when(principals.current()).thenReturn(approver());
        when(humanTasks.findForUpdate("htk1")).thenReturn(Optional.of(task("SUBMITTED")));

        service.submit("htk1", Map.of());

        verify(humanTasks, never()).submit(any(), any(), any());
        verify(turns, never()).requeueFromWaitingApproval(any());
    }

    @Test
    void submitRejectsNonPendingTask() {
        when(principals.current()).thenReturn(approver());
        when(humanTasks.findForUpdate("htk1")).thenReturn(Optional.of(task("EXPIRED")));

        BizException e = assertThrows(BizException.class, () -> service.submit("htk1", Map.of()));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        verify(humanTasks, never()).submit(any(), any(), any());
    }
}
