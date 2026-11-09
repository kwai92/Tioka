package com.tioka.server.orch;

import com.tioka.framework.error.BizException;
import com.tioka.orchestration.application.pause.RulePauseService;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.OrchRuleRepository;
import com.tioka.orchestration.infrastructure.mapper.OrchDecisionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchEventMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchFactMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchMandateMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchSubscriptionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchWorkItemMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.notify.NotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORC-07（M2-00 第 75 行）规则自动暂停——离线用例（M2 批次 B-6a 实现后转绿）。
 *
 * <p>行为规格（M2-01「规则自动暂停」第 165–167 行 / ADR-020 第 36 行）：Run 以
 * {@code FAILED} 结束时 {@code consecutive_failures + 1}，{@code SUCCEEDED} 清零；
 * 达到阈值（{@code tioka.orch.rule.pause-threshold}，默认 5）→ 规则进入暂停态
 * （{@code paused_at}/{@code paused_reason}，V131 列）+ 审计 {@code orch.rule.pause}
 * + 经 {@code NotificationPort}（约束 F21）告警规则负责人；暂停期规则不再参与派发
 * （{@code OrchEngineRepository#findActiveRulesForEventType} 跳过）；显式恢复
 * （REST {@code POST /api/orch/rules/{id}/resume}，{@code @Audited orch.rule.resume}）
 * 后重新参与决定。
 *
 * <p>本类纯 JUnit5 + Mockito，不依赖 Spring 上下文与数据库（离线），与
 * {@code HopsIT} 同一惯例。需要真实容器的部分（REST 端点 + 权限 + 审计 AOP 全链路
 * {@code PermissionAspect}/{@code AuditedAspect} 拦截）不在本类覆盖：
 * 恢复端点的动作名已登记 {@code docs/contracts/audit/actions.yaml}，由
 * {@code AuditedActionCatalogTest} 契约校验，端到端行为随 M3 FE-17 验收回归承接。
 */
@Tag("it")
class RulePauseIT {

    private static final String RULE_ID = "rule-1";
    private static final String VERSION_ID = "rv-1";
    private static final int THRESHOLD = 5;

    private OrchRuleRepository repo;
    private AuditApi audit;
    private NotificationPort notifications;
    private RulePauseService pauseService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(OrchRuleRepository.class);
        audit = mock(AuditApi.class);
        notifications = mock(NotificationPort.class);
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
        rule.setName("crm-followup");
        when(repo.findRule(RULE_ID)).thenReturn(rule);

        pauseService = new RulePauseService(repo, provider, audit, THRESHOLD);
    }

    private void failRun() {
        pauseService.recordRunOutcome(VERSION_ID, "FAILED");
    }

    /** 验收 1：连续失败达到阈值（第 5 次）→ markPaused + 审计 orch.rule.pause + 通知负责人。 */
    @Test
    void consecutiveFailuresPauseRule() {
        for (int i = 1; i <= THRESHOLD; i++) {
            failRun();
        }

        verify(repo, never()).clearPause(anyString());
        verify(repo).markPaused(eq(RULE_ID), any(LocalDateTime.class),
                eq(RulePauseService.PAUSE_REASON_CONSECUTIVE_FAILURES), eq(THRESHOLD));
        ArgumentCaptor<AuditEvent> aud = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(aud.capture());
        assertEquals("orch.rule.pause", aud.getValue().action());
        assertEquals(RULE_ID, aud.getValue().resourceId());
        verify(notifications).notify(any(NotificationPort.NotificationCommand.class));
    }

    /** 验收 4：未达阈值不受影响——前 4 次失败仅累加计数，不暂停、不审计、不通知。 */
    @Test
    void belowThresholdDoesNotPause() {
        for (int i = 1; i < THRESHOLD; i++) {
            failRun();
        }

        verify(repo, never()).markPaused(anyString(), any(), anyString(), anyInt());
        verify(audit, never()).record(any());
        verify(notifications, never()).notify(any());
        // 四次失败逐次累加 1..4，不触发暂停
        verify(repo, times(THRESHOLD - 1)).setConsecutiveFailures(eq(RULE_ID), anyInt());
        verify(repo).setConsecutiveFailures(RULE_ID, 1);
        verify(repo).setConsecutiveFailures(RULE_ID, THRESHOLD - 1);
    }

    /**
     * 验收 2：暂停期间 {@code findActiveRulesForEventType} 不再返回该规则（后续事件不派发），
     * 恢复（清 paused_at）后重新返回。
     */
    @Test
    void pausedRuleNotDispatched() {
        OrchRuleVersionDO stable = new OrchRuleVersionDO();
        stable.setId(VERSION_ID);
        stable.setRuleId(RULE_ID);
        stable.setEventType("crm.op.created");
        stable.setStage("STABLE");

        OrchRuleVersionMapper versions = mock(OrchRuleVersionMapper.class);
        when(versions.selectList(any())).thenReturn(List.of(stable));
        when(versions.selectById(VERSION_ID)).thenReturn(stable);
        OrchRuleMapper rules = mock(OrchRuleMapper.class);

        OrchEngineRepository engine = new OrchEngineRepository(
                mock(OrchEventMapper.class), mock(OrchDecisionMapper.class),
                mock(OrchWorkItemMapper.class), mock(OrchMandateMapper.class),
                mock(OrchSubscriptionMapper.class), mock(OrchRunMapper.class),
                mock(OrchFactMapper.class), rules, versions);

        // 未暂停 → 参与派发
        OrchRuleDO active = new OrchRuleDO();
        active.setId(RULE_ID);
        active.setStableVersionId(VERSION_ID);
        when(rules.selectById(RULE_ID)).thenReturn(active);
        assertEquals(List.of(active), engine.findActiveRulesForEventType("crm.op.created"));

        // 暂停（paused_at 非空，V131 列）→ 跳过，事件不再派发
        OrchRuleDO paused = new OrchRuleDO();
        paused.setId(RULE_ID);
        paused.setStableVersionId(VERSION_ID);
        paused.setPausedAt(LocalDateTime.now());
        paused.setPausedReason(RulePauseService.PAUSE_REASON_CONSECUTIVE_FAILURES);
        when(rules.selectById(RULE_ID)).thenReturn(paused);
        assertTrue(engine.findActiveRulesForEventType("crm.op.created").isEmpty(),
                "暂停期规则不得参与派发");
    }

    /** 验收 3：显式恢复入口 resume 清暂停标记，恢复后规则重新参与决定。 */
    @Test
    void resumeRestoresDispatch() {
        OrchRuleDO paused = repo.findRule(RULE_ID);
        paused.setPausedAt(LocalDateTime.now());
        paused.setPausedReason(RulePauseService.PAUSE_REASON_CONSECUTIVE_FAILURES);

        pauseService.resume(RULE_ID);
        verify(repo).clearPause(RULE_ID);

        // 模拟恢复后的 DB 状态（clearPause 置空 paused_at）→ findActiveRulesForEventType 重新返回
        OrchRuleVersionDO stable = new OrchRuleVersionDO();
        stable.setId(VERSION_ID);
        stable.setRuleId(RULE_ID);
        stable.setEventType("crm.op.created");
        stable.setStage("STABLE");
        OrchRuleVersionMapper versions = mock(OrchRuleVersionMapper.class);
        when(versions.selectList(any())).thenReturn(List.of(stable));
        when(versions.selectById(VERSION_ID)).thenReturn(stable);
        OrchRuleMapper rules = mock(OrchRuleMapper.class);
        OrchEngineRepository engine = new OrchEngineRepository(
                mock(OrchEventMapper.class), mock(OrchDecisionMapper.class),
                mock(OrchWorkItemMapper.class), mock(OrchMandateMapper.class),
                mock(OrchSubscriptionMapper.class), mock(OrchRunMapper.class),
                mock(OrchFactMapper.class), rules, versions);
        OrchRuleDO restored = new OrchRuleDO();
        restored.setId(RULE_ID);
        restored.setStableVersionId(VERSION_ID);
        when(rules.selectById(RULE_ID)).thenReturn(restored);
        assertEquals(List.of(restored), engine.findActiveRulesForEventType("crm.op.created"));
    }

    /** 恢复防御：未暂停规则调用 resume → 400（不产生任何写操作）。 */
    @Test
    void resumeNonPausedRuleRejected() {
        BizException e = assertThrows(BizException.class, () -> pauseService.resume(RULE_ID));
        assertTrue(String.valueOf(e.getMessage()).contains("未处于暂停"));
        verify(repo, never()).clearPause(anyString());
    }

    /** 成功收口清零：失败计数被 SUCCEEDED 中断后重新起算。 */
    @Test
    void successResetsCounter() {
        failRun();
        failRun();
        verify(repo, org.mockito.Mockito.times(2)).setConsecutiveFailures(anyString(), anyInt());

        pauseService.recordRunOutcome(VERSION_ID, "SUCCEEDED");
        verify(repo).setConsecutiveFailures(RULE_ID, 0);
        verify(repo, never()).markPaused(anyString(), any(), anyString(), eq(THRESHOLD));
    }
}
