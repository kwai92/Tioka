package com.tioka.runtime.kernel.approval;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.infrastructure.persistence.RuntimeHumanTaskDO;
import com.tioka.runtime.kernel.infrastructure.HumanTaskRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCH-09 / ADR-066 §7.4：人工补录任务超时兜底离线测试——到期任务对应 Turn 置
 * {@code FAILED(HUMAN_TIMEOUT)}；非 Turn 主体（Flow 节点 / 编排）不由本扫描处理。
 */
class HumanTaskExpirerTest {

    private HumanTaskRepository humanTasks;
    private TurnRepository turns;
    private PrincipalHolder principals;
    private HumanTaskExpirer expirer;

    @BeforeEach
    void setUp() {
        humanTasks = mock(HumanTaskRepository.class);
        turns = mock(TurnRepository.class);
        principals = mock(PrincipalHolder.class);
        expirer = new HumanTaskExpirer(humanTasks, turns, principals);
        // runAs(Principal, Supplier) 在单测里同步执行，便于断言
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get())
                .when(principals).runAs(any(), any(Supplier.class));
    }

    private static RuntimeHumanTaskDO task(String subjectType, String turnId) {
        RuntimeHumanTaskDO t = new RuntimeHumanTaskDO();
        t.setId("htk1");
        t.setSubjectType(subjectType);
        t.setTurnId(turnId);
        return t;
    }

    @Test
    void expiredTurnTaskFailsTurnWithHumanTimeout() {
        when(humanTasks.expireDue(any())).thenReturn(List.of("htk1"));
        when(humanTasks.findById("htk1")).thenReturn(Optional.of(task("turn", "turn1")));
        when(turns.failWaitingApproval(eq("turn1"), eq("HUMAN_TIMEOUT"), any())).thenReturn(1);

        expirer.expire();

        verify(turns).failWaitingApproval(eq("turn1"), eq("HUMAN_TIMEOUT"), any());
    }

    @Test
    void nonTurnSubjectIsSkipped() {
        when(humanTasks.expireDue(any())).thenReturn(List.of("htk1"));
        when(humanTasks.findById("htk1")).thenReturn(Optional.of(task("orch_run", "turn1")));

        expirer.expire();

        verify(turns, never()).failWaitingApproval(any(), any(), any());
    }
}
