package com.tioka.runtime.application.trust;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.runtime.infrastructure.TrustUndoCircuitPolicyRepository;
import com.tioka.runtime.infrastructure.TrustUndoCircuitRepository;
import com.tioka.runtime.infrastructure.persistence.TrustUndoCircuitDO;
import com.tioka.system.api.audit.AuditApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TRU-08 撤销熔断离线验收（M3-05 §6）：阈值触发、同窗口去重、窗口滚动、配置审计。 */
class UndoCircuitBreakerTest {

    private static final String AGENT = "agt_1";
    private static final String WS = "ws_1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 16, 0);

    @Test
    void thirdUndoOpensCircuitOncePerWindow() {
        Fixture f = new Fixture();
        f.row.setUndoCount(3);
        when(f.circuit.tryOpen(anyString(), any(), any(), anyString())).thenReturn(true, false);

        f.breaker.onUndo(AGENT, WS, "trn_1", NOW);
        f.breaker.onUndo(AGENT, WS, "trn_1", NOW);

        assertEquals(1, f.events.size(), "同一窗口只发一次熔断事件");
        DomainEvent event = f.events.get(0);
        assertEquals(GovernanceMqTopics.TRUST_UNDO_CIRCUIT_OPEN, event.event());
        assertEquals(GovernanceMqTopics.TOPIC, event.topic());
        assertEquals(AGENT, event.subjectKey());
        assertEquals("UNDO_THRESHOLD", event.payload().get("reason"));
        assertEquals(3, event.payload().get("undoCount"));
        verify(f.circuit, times(2)).tryOpen(anyString(), any(), any(), anyString());
    }

    @Test
    void compensationFailureCountAlsoOpensCircuit() {
        Fixture f = new Fixture();
        f.row.setUndoCount(1);
        f.row.setCompFailCount(1);
        when(f.circuit.tryOpen(anyString(), any(), any(), anyString())).thenReturn(true);

        f.breaker.onUndo(AGENT, WS, "trn_2", NOW);

        assertEquals(1, f.events.size());
        assertEquals(UndoCircuitBreaker.REASON_COMPENSATION_FAILURE,
                f.events.get(0).payload().get("reason"));
    }

    @Test
    void belowThresholdAndExpiredWindowDoNotOpen() {
        Fixture f = new Fixture();
        f.row.setUndoCount(2);
        f.breaker.onUndo(AGENT, WS, "trn_3", NOW);
        assertTrue(f.events.isEmpty());

        f.row.setUndoCount(5);
        f.row.setWindowStart(NOW.minusHours(2));
        f.breaker.onUndo(AGENT, WS, "trn_4", NOW);
        assertTrue(f.events.isEmpty(), "窗口已滚动，旧计数不得触发熔断");
    }

    @Test
    void missingPublisherDoesNotConsumeCircuitCas() {
        Fixture f = new Fixture();
        f.row.setUndoCount(3);
        f.breaker = new UndoCircuitBreaker(f.circuit, f.policies, provider(null), provider(null));

        assertThrows(IllegalStateException.class,
                () -> f.breaker.onUndo(AGENT, WS, "trn_5", NOW));
        // 不能在事件发不出去时先把 opened_at CAS 消耗掉，否则该窗口永久静默失效
        verify(f.circuit, org.mockito.Mockito.never())
                .tryOpen(anyString(), any(), any(), anyString());
        assertTrue(f.events.isEmpty());
    }

    @Test
    void configureWritesPolicyAndAudit() {
        Fixture f = new Fixture();
        AuditApi audit = mock(AuditApi.class);
        f.breaker = new UndoCircuitBreaker(f.circuit, f.policies, f.publishers, provider(audit));

        UndoCircuitPolicy policy = f.breaker.configure(WS, 5, 2, 30, "admin_1");

        assertEquals(5, policy.undoThreshold());
        assertEquals(30, policy.windowMinutes());
        verify(f.policies).upsert(any(UndoCircuitPolicy.class), anyString());
        verify(audit).recordIndependent(any());
    }

    private static final class Fixture {
        final TrustUndoCircuitRepository circuit = mock(TrustUndoCircuitRepository.class);
        final TrustUndoCircuitPolicyRepository policies = mock(TrustUndoCircuitPolicyRepository.class);
        final List<DomainEvent> events = new ArrayList<>();
        final ObjectProvider<EventPublisher> publishers;
        final TrustUndoCircuitDO row = new TrustUndoCircuitDO();
        UndoCircuitBreaker breaker;

        Fixture() {
            EventPublisher publisher = events::add;
            publishers = provider(publisher);
            when(policies.find(anyString())).thenReturn(UndoCircuitPolicy.defaults(WS));
            row.setAgentId(AGENT);
            row.setWorkspaceId(WS);
            row.setUndoCount(0);
            row.setCompFailCount(0);
            row.setWindowStart(NOW.minusMinutes(5));
            when(circuit.find(AGENT)).thenReturn(row);
            breaker = new UndoCircuitBreaker(circuit, policies, publishers, provider(null));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> payload(DomainEvent e) {
        return e.payload();
    }
}
