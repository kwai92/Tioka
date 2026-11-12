package com.tioka.governance.application.autonomy;

import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyLevel;
import com.tioka.governance.api.charter.AgentCharterPort;
import com.tioka.governance.infrastructure.autonomy.TrustAutonomyDemotionRepository;
import com.tioka.system.api.notify.NotificationPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TRU-10 自动降级离线验收（M3-05 §8）：降一级 / 全类别 L1 / 重复事件只降一次 / 通知负责人。 */
class AutonomyDemotionServiceTest {

    private static final String AGENT = "agt_1";
    private static final String WS = "ws_1";

    @Test
    void actionUndoneDemotesOneLevelAndNotifiesOwner() {
        Fixture f = new Fixture();
        f.service.demoteOne(AGENT, WS, "ar.writeoff", "undo:trn_1:ar.writeoff", "evt_1", "undo");

        assertEquals(1, f.levels.get("ar.writeoff"), "L2 → L1");
        assertEquals(3, f.levels.get("contract.review"), "其它类别不变");
        assertEquals(1, f.notices.size());
        assertTrue(f.notices.get(0).dedupKey().startsWith("autonomy-demote:"));
        assertEquals("trust.autonomy.demoted", f.notices.get(0).template());
        assertEquals("workspace:" + WS, f.notices.get(0).recipient());
    }

    @Test
    void repeatedEventOnlyDemotesOnce() {
        Fixture f = new Fixture();
        String key = "undo:trn_1:ar.writeoff";
        f.service.demoteOne(AGENT, WS, "ar.writeoff", key, "evt_1", "undo");
        f.service.demoteOne(AGENT, WS, "ar.writeoff", key, "evt_1", "undo");

        assertEquals(1, f.levels.get("ar.writeoff"));
        verify(f.autonomy, times(1)).setLevel(anyString(), anyString(), any(AutonomyLevel.class), anyString());
        assertEquals(1, f.notices.size(), "重复投递不得重复发卡片");
    }

    @Test
    void circuitDropsAllClassesToL1() {
        Fixture f = new Fixture();
        f.service.demoteAll(AGENT, WS, "circuit:agt_1:w1", "evt_2", "circuit");

        assertEquals(1, f.levels.get("ar.writeoff"));
        assertEquals(1, f.levels.get("contract.review"));
        assertEquals(1, f.notices.size());
    }

    @Test
    void repeatedCircuitEventIsIdempotent() {
        Fixture f = new Fixture();
        f.service.demoteAll(AGENT, WS, "circuit:agt_1:w1", "evt_2", "circuit");
        f.service.demoteAll(AGENT, WS, "circuit:agt_1:w1", "evt_2", "circuit");

        verify(f.autonomy, times(2)).setLevel(anyString(), anyString(), any(AutonomyLevel.class), anyString());
        assertEquals(1, f.notices.size());
    }

    @Test
    void notificationPortAbsentDoesNotBlockDemotion() {
        Fixture f = new Fixture();
        AutonomyDemotionService silent = new AutonomyDemotionService(
                f.autonomy, f.ledger, provider(null), provider(null));
        silent.demoteOne(AGENT, WS, "ar.writeoff", "undo:trn_9:ar.writeoff", "evt_9", "undo");
        assertEquals(1, f.levels.get("ar.writeoff"));
        assertTrue(f.notices.isEmpty());
    }

    private static final class Fixture {
        final AutonomyApi autonomy = mock(AutonomyApi.class);
        final InMemoryLedger ledger = new InMemoryLedger();
        final List<NotificationPort.NotificationCommand> notices = new java.util.ArrayList<>();
        final Map<String, Integer> levels = new HashMap<>();
        final AutonomyDemotionService service;

        Fixture() {
            levels.put("ar.writeoff", 2);
            levels.put("contract.review", 3);
            when(autonomy.levelOf(anyString(), anyString())).thenAnswer(inv ->
                    levels.getOrDefault(inv.getArgument(1, String.class), 1));
            when(autonomy.list(AGENT)).thenAnswer(inv -> levels.keySet().stream()
                    .map(k -> new AutonomyApi.AutonomyView(AGENT, k, levels.get(k), null, null))
                    .toList());
            doAnswer(inv -> {
                levels.put(inv.getArgument(1, String.class),
                        ((AutonomyLevel) inv.getArgument(2)).ordinalLevel());
                return null;
            }).when(autonomy).setLevel(anyString(), anyString(), any(AutonomyLevel.class), anyString());
            NotificationPort port = notices::add;
            service = new AutonomyDemotionService(autonomy, ledger, provider(port),
                    provider(mock(AgentCharterPort.class)));
        }
    }

    /** 内存幂等台账：与 V226 ON CONFLICT DO NOTHING 语义一致。 */
    private static final class InMemoryLedger extends TrustAutonomyDemotionRepository {
        final Set<String> keys = new HashSet<>();

        InMemoryLedger() {
            super(null);
        }

        @Override
        public boolean tryReserve(String demotionKey, String workspaceId, String agentId,
                                  String actionClass, String eventId, String fromLevel, String toLevel) {
            return keys.add(demotionKey);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
