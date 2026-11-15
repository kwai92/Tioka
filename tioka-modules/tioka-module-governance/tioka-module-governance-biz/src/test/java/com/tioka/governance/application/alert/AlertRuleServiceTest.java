package com.tioka.governance.application.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.alert.AlertComparator;
import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertRuleCommand;
import com.tioka.governance.api.alert.AlertSeverity;
import com.tioka.governance.api.alert.AlertTargetType;
import com.tioka.governance.infrastructure.alert.AlertChangeLog;
import com.tioka.governance.infrastructure.alert.OpsAlertRepository;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GOV-13 规则管理离线验收（ADR-041 §5.3）：校验、鉴权、写 cfg_change 流水、内置规则播种。 */
class AlertRuleServiceTest {

    private final OpsAlertRepository repo = mock(OpsAlertRepository.class);
    private final AlertChangeLog changeLog = mock(AlertChangeLog.class);
    private final AlertProperties props = new AlertProperties();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("创建：写入规则并记 cfg_change 流水（reason=create）")
    void createWritesRuleAndChangeLog() {
        AlertRuleService service = service(true);
        when(repo.listAllRules()).thenReturn(List.of());

        service.create(metricCommand());

        ArgumentCaptor<OpsAlertRuleDO> captor = ArgumentCaptor.forClass(OpsAlertRuleDO.class);
        verify(repo).insertRule(captor.capture());
        assertTrue(captor.getValue().getId().startsWith("alrt_"));
        assertEquals("ALL", captor.getValue().getTargetType());
        verify(changeLog).record(anyString(), isNull(), anyString(), eq("u1"), eq("create"), isNull());
    }

    @Test
    @DisplayName("校验：名称空 / 状态条件缺阈值文本 / 指标条件缺阈值 / 静默起止不成对")
    void validationRejectsBadCommands() {
        AlertRuleService service = service(true);
        assertThrows(BizException.class, () -> service.create(new AlertRuleCommand(
                " ", AlertTargetType.ALL, null, AlertConditionType.QUOTA_WARN,
                AlertComparator.GTE, 0.8, null, 0, AlertSeverity.WARNING,
                List.of("in_app"), List.of(), null, null, true, null)));

        assertThrows(BizException.class, () -> service.create(new AlertRuleCommand(
                "状态规则", AlertTargetType.VENDOR, "prov_1", AlertConditionType.HEALTH_STATUS,
                AlertComparator.EQ, null, null, 0, AlertSeverity.CRITICAL,
                List.of("in_app"), List.of(), null, null, true, null)));

        assertThrows(BizException.class, () -> service.create(new AlertRuleCommand(
                "指标规则", AlertTargetType.ALL, null, AlertConditionType.ERROR_RATE,
                AlertComparator.GT, null, null, 5, AlertSeverity.WARNING,
                List.of("in_app"), List.of(), null, null, true, null)));

        assertThrows(BizException.class, () -> service.create(new AlertRuleCommand(
                "静默规则", AlertTargetType.ALL, null, AlertConditionType.QUOTA_WARN,
                AlertComparator.GTE, 0.8, null, 0, AlertSeverity.WARNING,
                List.of("in_app"), List.of(), "22:00", null, true, null)));

        assertThrows(BizException.class, () -> service.create(new AlertRuleCommand(
                "非ALL缺对象", AlertTargetType.VENDOR, null, AlertConditionType.HEALTH_STATUS,
                AlertComparator.EQ, null, "DOWN", 0, AlertSeverity.CRITICAL,
                List.of("in_app"), List.of(), null, null, true, null)));
    }

    @Test
    @DisplayName("鉴权：无 ops:alert:admin 时写操作被拒")
    void writeRequiresPermission() {
        AlertRuleService service = service(false);
        BizException ex = assertThrows(BizException.class, () -> service.create(metricCommand()));
        assertTrue(ex.getMessage().contains(Permissions.OPS_ALERT_ADMIN));
        verify(repo, never()).insertRule(any());
    }

    @Test
    @DisplayName("内置规则播种：按名字幂等，缺失才插入全部缺省规则")
    void seedBuiltinRulesIsIdempotent() {
        AlertRuleService service = service(true);
        when(repo.findBuiltinRuleByName(anyString())).thenReturn(Optional.empty());

        int expected = BuiltinAlertRules.defaults().size();
        assertEquals(expected, service.seedBuiltinRules());
        verify(repo, times(expected)).insertRule(any());
    }

    @Test
    @DisplayName("内置规则已存在时不重复播种")
    void seedSkipsExisting() {
        AlertRuleService service = service(true);
        when(repo.findBuiltinRuleByName(anyString())).thenReturn(Optional.of(new OpsAlertRuleDO()));

        assertEquals(0, service.seedBuiltinRules());
        verify(repo, never()).insertRule(any());
    }

    @Test
    @DisplayName("启停：更新启用位并记流水（reason=enable/disable）")
    void toggleUpdatesAndLogs() {
        AlertRuleService service = service(true);
        OpsAlertRuleDO row = new OpsAlertRuleDO();
        row.setId("alrt_1");
        row.setName("规则");
        row.setTargetType("ALL");
        row.setConditionType("QUOTA_WARN");
        row.setThresholdValue(0.8);
        when(repo.findRule("alrt_1")).thenReturn(Optional.of(row));

        service.toggle("alrt_1", false);

        verify(repo).updateRule(row);
        verify(changeLog).record(eq("alrt_1"), anyString(), anyString(), eq("u1"), eq("disable"), isNull());
    }

    @Test
    @DisplayName("内置规则不可删除")
    void deleteRejectsBuiltin() {
        AlertRuleService service = service(true);
        OpsAlertRuleDO row = new OpsAlertRuleDO();
        row.setId("alrt_b");
        row.setBuiltin(true);
        when(repo.findRule("alrt_b")).thenReturn(Optional.of(row));

        BizException ex = assertThrows(BizException.class, () -> service.delete("alrt_b"));
        assertTrue(ex.getMessage().contains("内置"));
        verify(repo, never()).deleteRule(anyString());
    }

    private AlertRuleService service(boolean permitted) {
        PrincipalHolder holder = mock(PrincipalHolder.class);
        Principal p = new Principal("u1", null, PrincipalType.USER, Set.of(),
                permitted ? Set.of(Permissions.OPS_ALERT_ADMIN, Permissions.OPS_MONITOR) : Set.of(),
                "password");
        when(holder.current()).thenReturn(p);
        when(holder.find()).thenReturn(Optional.of(p));
        return new AlertRuleService(repo, changeLog, props, holder, mapper);
    }

    private static AlertRuleCommand metricCommand() {
        return new AlertRuleCommand("配额预警", AlertTargetType.ALL, null,
                AlertConditionType.QUOTA_WARN, AlertComparator.GTE, 0.8, null, 0,
                AlertSeverity.WARNING, List.of("in_app"), List.of(), null, null, true, "测试");
    }
}
