package com.tioka.governance.application.alert;

import com.tioka.governance.api.alert.AlertState;
import com.tioka.governance.infrastructure.alert.OpsAlertRepository;
import com.tioka.governance.infrastructure.persistence.OpsAlertEventDO;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;
import com.tioka.system.api.notify.NotificationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GOV-13 事件落库与通知离线验收（ADR-041 §5.3「去重与恢复」）。 */
class AlertEmitterTest {

    private static final Instant AT = Instant.parse("2026-11-15T04:00:00Z");

    private final OpsAlertRepository repo = mock(OpsAlertRepository.class);
    private final List<NotificationPort.NotificationCommand> sent = new ArrayList<>();

    @Test
    @DisplayName("非事件条件：状态未变不落库；变化则落库并外发通知")
    void dedupAndNotify() {
        AlertEmitter emitter = emitter(true);
        when(repo.findLatestEvent("alrt_1", "prov_1", "HEALTH_STATUS"))
                .thenReturn(Optional.of(event("FIRING")));

        assertFalse(emitter.emit(rule(), outcome(AlertState.FIRING, "prov_1"), false),
                "已 FIRING 再命中不重复发");
        verify(repo, never()).insertEvent(any());

        when(repo.findLatestEvent("alrt_1", "prov_1", "HEALTH_STATUS")).thenReturn(Optional.empty());
        assertTrue(emitter.emit(rule(), outcome(AlertState.FIRING, "prov_1"), false));
        verify(repo).insertEvent(any());
        assertEquals(1, sent.size(), "命中应外发一条通知");
        assertEquals("alert.firing", sent.get(0).template());
        verify(repo).markNotified(any());
    }

    @Test
    @DisplayName("恢复通知：由 FIRING → RESOLVED 落库并外发 alert.resolved")
    void resolvedNotifies() {
        AlertEmitter emitter = emitter(true);
        when(repo.findLatestEvent("alrt_1", "prov_1", "HEALTH_STATUS"))
                .thenReturn(Optional.of(event("FIRING")));

        assertTrue(emitter.emit(rule(), outcome(AlertState.RESOLVED, "prov_1"), false));
        assertEquals(1, sent.size());
        assertEquals("alert.resolved", sent.get(0).template());
    }

    @Test
    @DisplayName("从未触发过时不上报恢复")
    void resolveWithoutHistoryIsSkipped() {
        AlertEmitter emitter = emitter(true);
        when(repo.findLatestEvent(any(), any(), any())).thenReturn(Optional.empty());

        assertFalse(emitter.emit(rule(), outcome(AlertState.RESOLVED, "prov_1"), false));
        verify(repo, never()).insertEvent(any());
    }

    @Test
    @DisplayName("静默时段：只入事件、不外发通知")
    void silencedOnlyRecordsEvent() {
        AlertEmitter emitter = emitter(true);
        when(repo.findLatestEvent(any(), any(), any())).thenReturn(Optional.empty());
        OpsAlertRuleDO rule = rule();
        rule.setSilenceStart("00:00");
        rule.setSilenceEnd("23:59");

        assertTrue(emitter.emit(rule, outcome(AlertState.FIRING, "prov_1"), false));
        verify(repo).insertEvent(any());
        assertTrue(sent.isEmpty(), "静默期内不得外发通知");
        verify(repo, never()).markNotified(any());
    }

    @Test
    @DisplayName("事件条件：每次观测各落库一条并推进游标水位")
    void eventConditionRecordsAndAdvancesCursor() {
        AlertEmitter emitter = emitter(false);
        when(repo.findLatestEvent(any(), any(), any())).thenReturn(Optional.of(event("FIRING")));

        assertTrue(emitter.emit(rule(), outcome(AlertState.FIRING, "model_binding"), true));
        verify(repo).insertEvent(any());
        ArgumentCaptor<OpsAlertEventDO> captor = ArgumentCaptor.forClass(OpsAlertEventDO.class);
        verify(repo).insertEvent(captor.capture());
        assertEquals("model_binding", captor.getValue().getTargetId());
        assertTrue(captor.getValue().getDedupKey().contains("model_binding"));
    }

    @Test
    @DisplayName("通知端口缺席：仍落库，不外发")
    void noNotificationPortStillRecords() {
        AlertEmitter emitter = emitter(false);
        when(repo.findLatestEvent(any(), any(), any())).thenReturn(Optional.empty());

        assertTrue(emitter.emit(rule(), outcome(AlertState.FIRING, "prov_1"), false));
        verify(repo).insertEvent(any());
        assertTrue(sent.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private AlertEmitter emitter(boolean withPort) {
        ObjectProvider<NotificationPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(withPort ? sent::add : null);
        return new AlertEmitter(repo, provider, new AlertProperties());
    }

    private static OpsAlertRuleDO rule() {
        OpsAlertRuleDO rule = new OpsAlertRuleDO();
        rule.setId("alrt_1");
        rule.setName("供应商 DOWN");
        rule.setTargetType("VENDOR");
        rule.setTargetId("prov_1");
        rule.setConditionType("HEALTH_STATUS");
        rule.setSeverity("CRITICAL");
        rule.setChannels("in_app");
        rule.setRecipients("u1");
        return rule;
    }

    private static OpsAlertEventDO event(String state) {
        OpsAlertEventDO e = new OpsAlertEventDO();
        e.setState(state);
        return e;
    }

    private static AlertEngine.Outcome outcome(AlertState state, String targetId) {
        return new AlertEngine.Outcome(targetId, state, 0.9, 0.8, "测试消息", AT);
    }
}
