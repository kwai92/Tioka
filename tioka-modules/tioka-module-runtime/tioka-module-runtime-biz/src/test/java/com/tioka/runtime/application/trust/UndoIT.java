package com.tioka.runtime.application.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolInvocation;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.application.tool.DailyCounterStore;
import com.tioka.runtime.infrastructure.TrustUndoCircuitRepository;
import com.tioka.runtime.infrastructure.TrustUndoRequestRepository;
import com.tioka.runtime.infrastructure.persistence.TrustUndoRequestDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.testkit.FakeErp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TRU-07 撤销与补偿离线验收（M3-05 §5.4 / ADR-033「撤销」）。
 * FakeErp 桩 + 内存 trust_action 存取 + 消息捕获，离线优先；
 * {@code UndoDualInstanceIT}（双实例）归 CI 登记。
 */
class UndoIT {

    private static final String TURN = "turn_u1";
    private static final String RUN = "run_u1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryTrustActionStore store = new InMemoryTrustActionStore(mapper);
    private final FakeErp erp = new FakeErp();
    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();
    private final EventPublisher publisher = events::add;
    private final List<Map.Entry<String, Long>> counterOps = new ArrayList<>();
    private final InMemoryCounters counters = new InMemoryCounters();
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);

    private UndoService undoService;
    private TrustCompensator compensator;
    private TrustUndoCircuitRepository circuit;
    private TrustUndoRequestRepository requests;

    /** 内存日累计（断言扣回）。 */
    private final class InMemoryCounters implements DailyCounterStore {
        final Map<String, long[]> data = new LinkedHashMap<>();

        @Override
        public DailyUsage add(String agentId, String actionClass, LocalDate day,
                              long amountMicros, long records) {
            long[] v = data.computeIfAbsent(key(actionClass), k -> new long[2]);
            v[0] += amountMicros;
            v[1] += records;
            return new DailyUsage(v[0], v[1], false);
        }

        @Override
        public DailyUsage deduct(String agentId, String actionClass, LocalDate day,
                                 long amountMicros, long records) {
            counterOps.add(Map.entry(actionClass, amountMicros));
            long[] v = data.computeIfAbsent(key(actionClass), k -> new long[2]);
            v[0] = Math.max(0, v[0] - amountMicros);
            v[1] = Math.max(0, v[1] - records);
            return new DailyUsage(v[0], v[1], false);
        }

        @Override
        public DailyUsage get(String agentId, String actionClass, LocalDate day) {
            long[] v = data.get(key(actionClass));
            return v == null ? DailyUsage.zero() : new DailyUsage(v[0], v[1], false);
        }

        @Override
        public DailyUsage rebuild(String agentId, String actionClass, LocalDate day) {
            return get(agentId, actionClass, day);
        }

        private String key(String actionClass) {
            return "agt_1:" + actionClass;
        }
    }

    /** 内存撤销请求记录（UNIQUE(scope) 幂等语义）。 */
    private static final class InMemoryRequests extends TrustUndoRequestRepository {
        final Map<String, TrustUndoRequestDO> byScope = new LinkedHashMap<>();

        InMemoryRequests() {
            super(null);
        }

        @Override
        public TrustUndoRequestDO findByScope(String scopeType, String scopeId) {
            return byScope.get(scopeType + ":" + scopeId);
        }

        @Override
        public void insert(TrustUndoRequestDO row) {
            String key = row.getScopeType() + ":" + row.getScopeId();
            if (byScope.containsKey(key)) {
                throw new IllegalStateException("duplicate scope (UNIQUE)");
            }
            byScope.put(key, row);
        }

        @Override
        public void update(TrustUndoRequestDO row) {
            byScope.put(row.getScopeType() + ":" + row.getScopeId(), row);
        }
    }

    /** 内存熔断计数（TRU-07/08）。 */
    private static final class InMemoryCircuit extends TrustUndoCircuitRepository {
        int undoCount;
        int compFailCount;

        InMemoryCircuit() {
            super(null);
        }

        @Override
        public void recordUndo(String agentId, String workspaceId, LocalDateTime now) {
            undoCount++;
        }

        @Override
        public void recordCompensationFailure(String agentId, String workspaceId, LocalDateTime now) {
            compFailCount++;
        }
    }

    @BeforeEach
    void setUp() {
        for (int i = 1; i <= 5; i++) {
            erp.seedOrder("ord_" + i, "OPEN", 1_000_000L * i);
        }
        ToolRegistry registry = new ToolRegistry(List.of(erp));
        PreparedCallStore preparedCalls = new PreparedCallStore(store, mapper, null);
        requests = new InMemoryRequests();
        circuit = new InMemoryCircuit();
        UndoAuthorizer authorizer = new UndoAuthorizer(turnRepoInitiator("user_1"),
                approvals, fixedProvider(snapshotOwner("owner_1")), Optional.empty(), mapper);
        undoService = new UndoService(store, requests, circuit, preparedCalls, publisher,
                authorizer, auditNone(), mapper);
        compensator = new TrustCompensator(store, requests, circuit, preparedCalls, registry,
                counters, publisher, approvals, snapshotOwner("owner_1"), mapper);
        Principal principal = user("user_1");
        // 5 步写操作（FakeErp 实际执行，拿回执）
        for (int i = 1; i <= 5; i++) {
            ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_UPDATE_ORDER,
                    Map.of("orderId", "ord_" + i, "status", "SHIPPED"));
            store.seedApplied(erp, "act_" + i, TURN, RUN, i,
                    FakeErp.TOOL_UPDATE_ORDER, "order.update", Reversibility.REVERSIBLE,
                    inv, "ws_1");
            counters.add("agt_1", "order.update", LocalDate.now(), 1_000_000L, 1);
        }
        assertNotNull(principal);
    }

    private static <T> ObjectProvider<T> fixedProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private Principal user(String userId) {
        return new Principal(userId, "ws_1", PrincipalType.USER,
                java.util.Set.of(), java.util.Set.of(), "password");
    }

    private ObjectProvider<com.tioka.system.api.audit.AuditApi> auditNone() {
        return new ObjectProvider<>() {
            @Override
            public com.tioka.system.api.audit.AuditApi getObject() {
                return null;
            }

            @Override
            public com.tioka.system.api.audit.AuditApi getObject(Object... args) {
                return null;
            }

            @Override
            public com.tioka.system.api.audit.AuditApi getIfAvailable() {
                return null;
            }

            @Override
            public com.tioka.system.api.audit.AuditApi getIfUnique() {
                return null;
            }
        };
    }

    private static TurnRepository turnRepoInitiator(String userId) {
        TurnRepository repo = mock(TurnRepository.class);
        com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO turn =
                new com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO();
        turn.setId(TURN);
        turn.setRunAsJson("{\"userId\":\"" + userId + "\",\"workspaceId\":\"ws_1\"}");
        when(repo.findById(TURN)).thenReturn(Optional.of(turn));
        return repo;
    }

    private static com.tioka.agent.api.AgentSnapshotApi snapshotOwner(String ownerId) {
        com.tioka.agent.api.AgentSnapshotApi api = mock(com.tioka.agent.api.AgentSnapshotApi.class);
        com.tioka.agent.api.AgentSnapshotApi.AgentSnapshot snap =
                new com.tioka.agent.api.AgentSnapshotApi.AgentSnapshot(
                        "av_1", "agt_1", 1, "ws_1", ownerId, "hash", null, null);
        when(api.currentPublished("agt_1")).thenReturn(snap);
        when(api.get("av_1")).thenReturn(snap);
        return api;
    }

    
    private List<Map<String, Object>> compensatePayloads() {
        return events.stream()
                .filter(e -> GovernanceMqTopics.TRUST_COMPENSATE.equals(e.event()))
                .map(DomainEvent::payload)
                .toList();
    }

    private UndoService.UndoResult undoRun() {
        return undoService.undo("run", RUN, user("user_1"));
    }

    @Test
    void fiveStepsRestored() {
        // 撤销前快照
        Map<String, Map<String, Object>> before = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            before.put("ord_" + i, erp.order("ord_" + i));
        }

        UndoService.UndoResult result = undoRun();
        assertEquals(5, result.compensating().size(), "5 步全部将补偿");
        assertTrue(result.irreversible().isEmpty());
        assertEquals("OPEN", result.status());

        // 补偿消息按 seq 倒序取动作（act_5→act_1），消息顺序号为补偿顺序 1→5，分区键 = 撤销范围 ID
        List<Map<String, Object>> payloads = compensatePayloads();
        assertEquals(5, payloads.size());
        assertEquals("act_5", payloads.get(0).get("actionId"), "倒序：最高 seq 先补");
        assertEquals("act_1", payloads.get(4).get("actionId"));
        assertEquals(1, payloads.get(0).get("seq"));
        assertEquals(5, payloads.get(4).get("seq"));
        assertEquals(RUN, payloads.get(0).get("undoScopeId"));
        events.stream().filter(e -> GovernanceMqTopics.TRUST_COMPENSATE.equals(e.event()))
                .allMatch(e -> RUN.equals(e.subjectKey()));

        // 按顺序消费补偿
        for (Map<String, Object> p : payloads) {
            compensator.routeLocally(p);
        }

        // FakeErp 状态与执行前快照完全一致（倒序恢复后 = 初始）
        for (int i = 1; i <= 5; i++) {
            assertEquals(before.get("ord_" + i), erp.order("ord_" + i), "ord_" + i + " 已复原");
        }
        // trust_action 全部 COMPENSATED
        for (int i = 1; i <= 5; i++) {
            assertEquals("COMPENSATED", store.findById("act_" + i).getStatus());
        }
        // 当日累计扣回
        assertEquals(0L, counters.get("agt_1", "order.update", LocalDate.now()).amountMicros());
        assertEquals(0L, counters.get("agt_1", "order.update", LocalDate.now()).records());
        // F31 事件（action_undone）
        long undone = events.stream()
                .filter(e -> GovernanceMqTopics.TRUST_ACTION_UNDONE.equals(e.event())).count();
        assertEquals(5, undone);
        // EVO-01：撤销登记事件（eval-biz 消费写负向反馈）
        long recorded = events.stream()
                .filter(e -> GovernanceMqTopics.TRUST_UNDO_RECORDED.equals(e.event())).count();
        assertEquals(1, recorded);
        assertEquals(RUN, events.stream()
                .filter(e -> GovernanceMqTopics.TRUST_UNDO_RECORDED.equals(e.event()))
                .findFirst().orElseThrow().payload().get("scopeId"));
        // 熔断计数（TRU-07 写计数，联动登记 TRU-10）
        assertEquals(1, ((InMemoryCircuit) circuit).undoCount);
    }

    @Test
    void compensationFailureStops() {
        UndoService.UndoResult result = undoRun();
        List<Map<String, Object>> payloads = compensatePayloads();
        assertEquals(5, payloads.size());

        // 前两步（act_5、act_4）成功；从第 3 步（act_3）开始注入补偿失败
        compensator.routeLocally(payloads.get(0));
        compensator.routeLocally(payloads.get(1));
        erp.setCompensateFail(true);
        compensator.routeLocally(payloads.get(2));

        // 第 3 条（act_3）COMPENSATION_FAILED；生成负责人人审；发 compensation_failed
        assertEquals("COMPENSATION_FAILED", store.findById("act_3").getStatus());
        assertEquals(3, store.findById("act_3").getUndoAttempts(), "3 次重试（指数退避）");
        long failedEvents = events.stream()
                .filter(e -> GovernanceMqTopics.TRUST_COMPENSATION_FAILED.equals(e.event())).count();
        assertEquals(1, failedEvents);
        ArgumentCaptor<com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO> attention =
                ArgumentCaptor.forClass(com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO.class);
        verify(approvals, atLeast(1)).insert(attention.capture());
        assertTrue(attention.getValue().getTitle().contains("补偿失败"));

        // 停止该组后续补偿：第 4 条消息（act_2）被跳过
        compensator.routeLocally(payloads.get(3));
        assertEquals("COMPENSATING", store.findById("act_2").getStatus(), "组失败后不再补偿");
        // 撤销请求标 FAILED
        assertEquals("FAILED", requests.findByScope("run", RUN).getStatus());
        assertEquals("act_3", requests.findByScope("run", RUN).getFailedActionId());
        assertEquals(1, ((InMemoryCircuit) circuit).compFailCount);
    }

    @Test
    void irreversibleListed() {
        // 把第 2 步改为不可逆
        store.findById("act_2").setReversibility("IRREVERSIBLE");

        UndoService.UndoResult result = undoRun();
        assertEquals(4, result.compensating().size());
        assertEquals(1, result.irreversible().size());
        assertEquals("act_2", result.irreversible().get(0).get("actionId"), "不可逆列入清单");

        List<Map<String, Object>> payloads = compensatePayloads();
        for (Map<String, Object> p : payloads) {
            compensator.routeLocally(p);
        }
        for (int i = 1; i <= 5; i++) {
            if (i == 2) {
                continue;
            }
            assertEquals("COMPENSATED", store.findById("act_" + i).getStatus());
        }
        assertEquals("APPLIED", store.findById("act_2").getStatus(), "不可逆动作保持不动");
    }

    @Test
    void windowClosed() {
        // 全部动作撤销期已过
        for (int i = 1; i <= 5; i++) {
            store.findById("act_" + i).setUndoDeadline(
                    LocalDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC));
        }
        BizException e = assertThrows(BizException.class, this::undoRun);
        assertSame(ErrorCode.UNDO_WINDOW_CLOSED, e.getErrorCode(), "超过撤销期返回 UNDO_WINDOW_CLOSED");
        assertEquals(0, compensatePayloads().size(), "过期不产生补偿消息");
    }

    @Test
    void idempotentDoubleClick() {
        UndoService.UndoResult first = undoRun();
        UndoService.UndoResult second = undoRun();
        assertEquals(first.undoId(), second.undoId(), "同范围第二次撤销返回第一次结果");
        assertEquals(5, compensatePayloads().size(), "只写一次补偿消息");
        // 第二次返回的清单与第一次一致
        assertEquals(first.compensating().size(), second.compensating().size());
    }

    @Test
    void unauthorizedUserRejected() {
        BizException e = assertThrows(BizException.class,
                () -> undoService.undo("run", RUN, user("stranger_9")));
        assertSame(ErrorCode.FORBIDDEN, e.getErrorCode());
        assertEquals(0, compensatePayloads().size());
    }

}
