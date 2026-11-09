package com.tioka.orchestration.application.pause;

import com.tioka.framework.error.BizException;
import com.tioka.orchestration.infrastructure.OrchRuleRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.notify.NotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORC-07 规则自动暂停/恢复——离线单元测试（纯 JUnit5 + Mockito，M2 批次 B-6a）。
 *
 * <p>口径（M2-01「规则自动暂停」/ ADR-020）：Run FAILED → {@code consecutive_failures + 1}，
 * SUCCEEDED → 清零；达阈值（默认 5）→ {@code markPaused}（V131 列）+ 审计
 * {@code orch.rule.pause} + 经通知端口告警负责人；CANCELLED/UNCERTAIN 不计数；
 * 恢复走显式入口 {@code resume}。
 */
class RulePauseServiceTest {

    private static final String RULE_ID = "rule-1";
    private static final String VERSION_ID = "rv-1";

    private OrchRuleRepository repo;
    private NotificationPort notifications;
    private AuditApi audit;
    private RulePauseService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(OrchRuleRepository.class);
        notifications = mock(NotificationPort.class);
        audit = mock(AuditApi.class);
        ObjectProvider<NotificationPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifications);

        OrchRuleVersionDO version = new OrchRuleVersionDO();
        version.setId(VERSION_ID);
        version.setRuleId(RULE_ID);
        when(repo.findVersion(VERSION_ID)).thenReturn(version);

        OrchRuleDO rule = new OrchRuleDO();
        rule.setId(RULE_ID);
        rule.setWorkspaceId("ws-1");
        rule.setOwnerId("owner-1");
        rule.setName("r");
        when(repo.findRule(RULE_ID)).thenReturn(rule);

        service = new RulePauseService(repo, provider, audit, 5);
    }

    private void failRun() {
        service.recordRunOutcome(VERSION_ID, "FAILED");
    }

    private void succeedRun() {
        service.recordRunOutcome(VERSION_ID, "SUCCEEDED");
    }

    private OrchRuleDO ruleWith(int failures) {
        OrchRuleDO rule = repo.findRule(RULE_ID);
        rule.setConsecutiveFailures(failures);
        return rule;
    }

    /** 未达阈值：只累加计数，不暂停。 */
    @Test
    void belowThresholdDoesNotPause() {
        ruleWith(3);
        failRun();

        verify(repo).setConsecutiveFailures(RULE_ID, 4);
        verify(repo, never()).markPaused(anyString(), any(), anyString(), anyInt());
        verify(audit, never()).record(any());
        verify(notifications, never()).notify(any());
    }

    /** 达阈值（第 5 次连续失败）：markPaused + 审计 orch.rule.pause + 通知负责人。 */
    @Test
    void thresholdTriggersPauseWithAuditAndNotice() {
        ruleWith(4);
        failRun();

        verify(repo).markPaused(eq(RULE_ID), any(), eq(RulePauseService.PAUSE_REASON_CONSECUTIVE_FAILURES), eq(5));
        ArgumentCaptor<AuditEvent> aud = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(aud.capture());
        AuditEvent event = aud.getValue();
        assertEquals("orch.rule.pause", event.action());
        assertEquals("orch_rule", event.resourceType());
        assertEquals(RULE_ID, event.resourceId());
        assertEquals("system", event.actorType());
        assertEquals("ws-1", event.workspaceId());
        assertEquals(Map.of(
                "consecutiveFailures", 5,
                "threshold", 5,
                "ruleName", "r"), event.details());

        ArgumentCaptor<NotificationPort.NotificationCommand> notice =
                ArgumentCaptor.forClass(NotificationPort.NotificationCommand.class);
        verify(notifications).notify(notice.capture());
        assertEquals("owner-1", notice.getValue().recipient());
        assertEquals("orch.rule.auto_paused", notice.getValue().template());
        assertEquals("orch-rule-paused:" + RULE_ID, notice.getValue().dedupKey());
    }

    /** 成功清零：连续失败中断后重新起算。 */
    @Test
    void successResetsFailureCount() {
        ruleWith(3);
        succeedRun();

        verify(repo).setConsecutiveFailures(RULE_ID, 0);
        verify(repo, never()).markPaused(anyString(), any(), anyString(), anyInt());
    }

    /** 成功清零后再失败：从 1 重新累计，不暂停。 */
    @Test
    void successThenFailuresRestartCounting() {
        ruleWith(4);
        succeedRun();
        ruleWith(0);
        failRun();

        verify(repo).setConsecutiveFailures(RULE_ID, 1);
        verify(repo, never()).markPaused(anyString(), any(), anyString(), anyInt());
    }

    /** CANCELLED / UNCERTAIN 等非 SUCCEEDED/FAILED 终态不计数。 */
    @Test
    void nonFailedTerminalStatusIgnored() {
        ruleWith(4);
        service.recordRunOutcome(VERSION_ID, "CANCELLED");
        service.recordRunOutcome(VERSION_ID, "UNCERTAIN");

        verify(repo, never()).setConsecutiveFailures(anyString(), anyInt());
        verify(repo, never()).markPaused(anyString(), any(), anyString(), anyInt());
    }

    /** 已暂停规则幂等：不再累加、不重复暂停/审计/通知。 */
    @Test
    void alreadyPausedRuleIsIdempotent() {
        OrchRuleDO paused = ruleWith(9);
        paused.setPausedAt(java.time.LocalDateTime.now());
        paused.setPausedReason(RulePauseService.PAUSE_REASON_CONSECUTIVE_FAILURES);
        failRun();

        verify(repo, never()).setConsecutiveFailures(anyString(), anyInt());
        verify(repo, never()).markPaused(anyString(), any(), anyString(), anyInt());
        verify(audit, never()).record(any());
    }

    /** 通知端口未接线（getIfAvailable = null）：暂停仍落库 + 审计，不报错。 */
    @Test
    @SuppressWarnings("unchecked")
    void pauseWorksWithoutNotificationPort() {
        OrchRuleVersionDO version = new OrchRuleVersionDO();
        version.setId(VERSION_ID);
        version.setRuleId(RULE_ID);
        when(repo.findVersion(VERSION_ID)).thenReturn(version);
        OrchRuleDO rule = ruleWith(4);
        when(repo.findRule(RULE_ID)).thenReturn(rule);

        ObjectProvider<NotificationPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RulePauseService noPort = new RulePauseService(repo, provider, audit, 5);
        noPort.recordRunOutcome(VERSION_ID, "FAILED");

        verify(repo).markPaused(eq(RULE_ID), any(), anyString(), eq(5));
        verify(audit).record(any());
        verify(notifications, never()).notify(any());
    }

    /** 审计写入失败不影响暂停落库（降级忽略）。 */
    @Test
    void auditFailureDoesNotBlockPause() {
        doThrow(new RuntimeException("audit down")).when(audit).record(any());
        ruleWith(4);
        failRun();

        verify(repo).markPaused(eq(RULE_ID), any(), anyString(), eq(5));
    }

    /** 通知发送失败不影响暂停落库（降级忽略）。 */
    @Test
    void notifyFailureDoesNotBlockPause() {
        doThrow(new RuntimeException("im down")).when(notifications).notify(any());
        ruleWith(4);
        failRun();

        verify(repo).markPaused(eq(RULE_ID), any(), anyString(), eq(5));
    }

    /** 恢复：清暂停标记与计数。 */
    @Test
    void resumeClearsPause() {
        OrchRuleDO paused = ruleWith(5);
        paused.setPausedAt(java.time.LocalDateTime.now());
        service.resume(RULE_ID);

        verify(repo).clearPause(RULE_ID);
    }

    /** 恢复：规则不存在 → NOT_FOUND。 */
    @Test
    void resumeUnknownRuleNotFound() {
        when(repo.findRule("rule-x")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.resume("rule-x"));
        assertTrue(String.valueOf(e.getMessage()).contains("不存在"));
    }

    /** 恢复：未暂停规则 → BAD_REQUEST。 */
    @Test
    void resumeNonPausedRuleRejected() {
        ruleWith(0);

        BizException e = assertThrows(BizException.class, () -> service.resume(RULE_ID));
        assertTrue(String.valueOf(e.getMessage()).contains("未处于暂停"));
        verify(repo, never()).clearPause(anyString());
    }

    /** 未知 version / rule 静默跳过（防御）。 */
    @Test
    void unknownVersionOrRuleIgnored() {
        service.recordRunOutcome("rv-x", "FAILED");
        when(repo.findRule(RULE_ID)).thenReturn(null);
        service.recordRunOutcome(VERSION_ID, "FAILED");

        verify(repo, never()).setConsecutiveFailures(anyString(), anyInt());
        verify(repo, times(0)).markPaused(anyString(), any(), anyString(), anyInt());
    }
}
