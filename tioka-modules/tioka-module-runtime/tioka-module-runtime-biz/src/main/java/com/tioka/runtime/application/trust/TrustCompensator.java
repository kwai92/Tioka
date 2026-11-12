package com.tioka.runtime.application.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.TransactionalTool;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.application.tool.DailyCounterStore;

import com.tioka.runtime.infrastructure.TrustUndoCircuitRepository;
import com.tioka.runtime.infrastructure.TrustUndoRequestRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.runtime.infrastructure.persistence.TrustUndoRequestDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 补偿消费者 {@code trust-compensator}（TRU-07 / ADR-033）。
 *
 * <ul>
 *   <li>按撤销范围 ID 分区（subjectKey = scopeId），同组顺序执行；幂等键 = actionId + ":undo"</li>
 *   <li>成功 → COMPENSATED + 当日累计扣回（{@link DailyCounterStore#deduct}）+
 *       发 {@code tioka.trust.action_undone.v1}（F31）</li>
 *   <li>失败重试 3 次（指数退避）→ COMPENSATION_FAILED + 停止该组后续补偿 +
 *       生成负责人人审（Attention，{@code runtime_approval} kind=CARD）+
 *       发 {@code tioka.trust.compensation_failed.v1}；不静默</li>
 * </ul>
 *
 * <p>消费注册按既有 {@code RocketMqConsumerBootstrap} 惯例（Spring bean 即订阅）。
 * 生产级跨实例重投由 Broker / 死信承接（登记 M3-C4 设计笔记）；进程内按序处理同分区消息。
 */
@org.springframework.stereotype.Component
public class TrustCompensator extends IdempotentConsumer<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(TrustCompensator.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int MAX_ATTEMPTS = 3;

    private final TrustActionStore actions;
    private final TrustUndoRequestRepository requests;
    private final TrustUndoCircuitRepository circuit;
    private final PreparedCallStore preparedCalls;
    private final ToolRegistry registry;
    private final ObjectProvider<DailyCounterStore> dailyCounters;
    private final ObjectProvider<EventPublisher> publishers;
    private final ApprovalRepository approvals;
    private final ObjectProvider<com.tioka.agent.api.AgentSnapshotApi> agentSnapshots;
    /** TRU-08 空间窗口口径（补偿失败计数与撤销计数必须同窗口）；离线装配可缺席。 */
    private final ObjectProvider<UndoCircuitBreaker> breakers;
    private final Backoff backoff;
    private final ObjectMapper mapper;

    /** 测试 / 离线装配（固定依赖 + 零退避）。 */
    public TrustCompensator(TrustActionStore actions,
                            TrustUndoRequestRepository requests,
                            TrustUndoCircuitRepository circuit,
                            PreparedCallStore preparedCalls,
                            ToolRegistry registry,
                            DailyCounterStore dailyCounters,
                            EventPublisher publisher,
                            ApprovalRepository approvals,
                            com.tioka.agent.api.AgentSnapshotApi agentSnapshots,
                            ObjectMapper mapper) {
        this(actions, requests, circuit, preparedCalls, registry,
                new FixedProvider<>(dailyCounters), new FixedProvider<>(publisher), approvals,
                new FixedProvider<>(agentSnapshots), new FixedProvider<UndoCircuitBreaker>(null),
                Backoff.NONE, mapper);
    }

    /** Spring 装配（RocketMqConsumerBootstrap 按 List&lt;IdempotentConsumer&gt; 拉起）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TrustCompensator(TrustActionStore actions,
                            TrustUndoRequestRepository requests,
                            TrustUndoCircuitRepository circuit,
                            PreparedCallStore preparedCalls,
                            ToolRegistry registry,
                            ObjectProvider<DailyCounterStore> dailyCounters,
                            ObjectProvider<EventPublisher> publishers,
                            ApprovalRepository approvals,
                            ObjectProvider<com.tioka.agent.api.AgentSnapshotApi> agentSnapshots,
                            ObjectProvider<UndoCircuitBreaker> breakers,
                            ObjectMapper mapper) {
        this(actions, requests, circuit, preparedCalls, registry, dailyCounters, publishers,
                approvals, agentSnapshots, breakers, Backoff.EXPONENTIAL, mapper);
    }

    public TrustCompensator(TrustActionStore actions,
                            TrustUndoRequestRepository requests,
                            TrustUndoCircuitRepository circuit,
                            PreparedCallStore preparedCalls,
                            ToolRegistry registry,
                            ObjectProvider<DailyCounterStore> dailyCounters,
                            ObjectProvider<EventPublisher> publishers,
                            ApprovalRepository approvals,
                            ObjectProvider<com.tioka.agent.api.AgentSnapshotApi> agentSnapshots,
                            ObjectProvider<UndoCircuitBreaker> breakers,
                            Backoff backoff,
                            ObjectMapper mapper) {
        this.actions = actions;
        this.requests = requests;
        this.circuit = circuit;
        this.preparedCalls = preparedCalls;
        this.registry = registry;
        this.dailyCounters = dailyCounters;
        this.publishers = publishers;
        this.approvals = approvals;
        this.agentSnapshots = agentSnapshots;
        this.breakers = breakers;
        this.backoff = backoff;
        this.mapper = mapper;
    }

    /** 退避策略（测试可注入零退避）。 */
    public interface Backoff {
        Backoff EXPONENTIAL = new Backoff() {
            @Override
            public void sleep(int attempt) {
                try {
                    Thread.sleep(100L << (attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        Backoff NONE = new Backoff() {
            @Override
            public void sleep(int attempt) {
                // 测试：零退避
            }
        };

        void sleep(int attempt);
    }

    private static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;

        FixedProvider(T value) {
            this.value = value;
        }

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
    }

    @Override
    protected String consumerName() {
        return "trust-compensator";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return GovernanceMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return GovernanceMqTopics.TRUST_COMPENSATE;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        String actionId = str(payload == null ? null : payload.get("actionId"));
        String undoScopeId = str(payload == null ? null : payload.get("undoScopeId"));
        if (actionId == null || actionId.isBlank()) {
            return;
        }
        TrustActionDO row = actions.findById(actionId);
        if (row == null) {
            log.warn("compensate 消息找不到动作 actionId={}", actionId);
            return;
        }
        // 幂等：只有 COMPENSATING 才执行补偿（COMPENSATED / FAILED / 其它态跳过）
        if (!PreparedCallStore.STATUS_COMPENSATING.equals(row.getStatus())) {
            log.debug("compensate 跳过非 COMPENSATING 动作 actionId={} status={}",
                    actionId, row.getStatus());
            return;
        }
        // 组已失败：停止该组后续补偿（ADR-033）
        TrustUndoRequestDO group = requests.findByScope(scopeTypeOf(row), undoScopeId);
        if (group != null && "FAILED".equals(group.getStatus())) {
            log.warn("撤销组已失败，跳过后续补偿 actionId={} scope={}", actionId, undoScopeId);
            return;
        }

        String idempotencyKey = actionId + ":undo";
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                compensateOnce(row, idempotencyKey);
                onCompensated(row, undoScopeId);
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("补偿失败 attempt={}/{} actionId={}", attempt, MAX_ATTEMPTS, actionId, e);
                // 本轮若已把状态推离 COMPENSATING（例如 onCompensated 的副作用异常），
                // 继续重试只会在 markCompensated 上碰撞，最终把一次成功补偿误判为失败并生成人审。
                // 此时抛出让投递事务整体回滚，由 outbox 重投从头走一遍（工具补偿本身幂等）。
                TrustActionDO current = actions.findById(actionId);
                if (current == null
                        || !PreparedCallStore.STATUS_COMPENSATING.equals(current.getStatus())) {
                    throw e;
                }
                int attempts = preparedCalls.bumpUndoAttempts(actionId);
                if (attempts >= MAX_ATTEMPTS) {
                    break;
                }
                backoff.sleep(attempt);
            }
        }
        onCompensationFailed(row, undoScopeId, last == null ? "unknown" : last.getMessage());
    }

    private static String scopeTypeOf(TrustActionDO row) {
        return row.getUndoScopeType() == null ? "action" : row.getUndoScopeType();
    }

    private void compensateOnce(TrustActionDO row, String idempotencyKey) {
        var resolved = registry.resolve(row.getAgentVersionId(), Map.of(), row.getToolName());
        if (resolved.isEmpty() || !(resolved.get().provider() instanceof TransactionalTool tx)) {
            throw new IllegalStateException("工具不可补偿或不存在: " + row.getToolName());
        }
        ChangeSet.ApplyReceipt receipt = readReceipt(row);
        if (receipt == null) {
            throw new IllegalStateException("回执缺失，无法补偿: " + row.getId());
        }
        ChangeSet.CompensationResult result = tx.compensate(receipt, idempotencyKey);
        if (result == null || !result.ok()) {
            throw new IllegalStateException("补偿被拒绝: "
                    + (result == null ? "no result" : result.message()));
        }
    }

    private void onCompensated(TrustActionDO row, String undoScopeId) {
        preparedCalls.markCompensated(row.getId());
        deductDailyUsage(row);
        publishActionUndone(row, undoScopeId);
        log.info("补偿完成 actionId={} scope={}", row.getId(), undoScopeId);
    }

    private void onCompensationFailed(TrustActionDO row, String undoScopeId, String reason) {
        preparedCalls.markCompensationFailed(row.getId(), reason);
        publishCompensationFailed(row, undoScopeId, reason);
        stopGroup(scopeTypeOf(row), undoScopeId == null ? row.getUndoScopeId() : undoScopeId,
                row.getId());
        openOwnerAttention(row, reason);
        try {
            // 与 UndoService 的撤销计数共用空间策略窗口：若这里固定 60 分钟，
            // 自定义 120 分钟窗口下第 90 分钟的补偿失败会误判窗口过期、
            // 把已累计的撤销次数清零，熔断与全类别降级不再触发。
            UndoCircuitBreaker breaker = breakers == null ? null : breakers.getIfAvailable();
            int windowMinutes = breaker == null ? 60 : breaker.windowMinutes(row.getWorkspaceId());
            circuit.recordCompensationFailure(row.getAgentId(), row.getWorkspaceId(),
                    LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC), windowMinutes);
        } catch (RuntimeException e) {
            log.warn("补偿失败熔断计数写入失败: {}", e.toString());
        }
        log.error("补偿最终失败 actionId={} scope={} reason={}", row.getId(), undoScopeId, reason);
    }

    private void stopGroup(String scopeType, String scopeId, String failedActionId) {
        if (scopeType == null || scopeId == null) {
            return;
        }
        TrustUndoRequestDO request = requests.findByScope(scopeType, scopeId);
        if (request != null) {
            request.setStatus("FAILED");
            request.setFailedActionId(failedActionId);
            requests.update(request);
        }
    }

    /** 负责人人审（Attention）：runtime_approval kind=CARD，assignee = Agent 负责人。 */
    private void openOwnerAttention(TrustActionDO row, String reason) {
        String owner = null;
        com.tioka.agent.api.AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
        try {
            if (snapshots != null) {
                owner = snapshots.currentPublished(row.getAgentId()).ownerId();
            }
        } catch (RuntimeException ignored) {
            // 快照缺失：assignee 留空，审批台按空间展示
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", "补偿失败，需要人工处理");
        card.put("actionId", row.getId());
        card.put("scopeId", row.getUndoScopeId());
        card.put("toolName", row.getToolName());
        card.put("reason", reason == null ? "" : reason);
        RuntimeApprovalDO approval = new RuntimeApprovalDO();
        approval.setId(Ids.newId("apr"));
        approval.setWorkspaceId(row.getWorkspaceId());
        approval.setSubjectType("turn");
        approval.setSubjectId(row.getTurnId());
        approval.setItemId(row.getItemId());
        approval.setKind("card");
        approval.setCardJson(writeJson(card));
        approval.setAssigneeJson(owner == null ? "{}" : "{\"userIds\":[\"" + owner + "\"]}");
        approval.setStatus("PENDING");
        approval.setTitle("补偿失败（动作 " + row.getId() + "）");
        approval.setAgentId(row.getAgentId());
        approval.setRisk(row.getRisk());
        approvals.insert(approval);
    }

    private void deductDailyUsage(TrustActionDO row) {
        DailyCounterStore counters = dailyCounters.getIfAvailable();
        if (counters == null || row.getActionClass() == null || row.getActionClass().isBlank()) {
            return;
        }
        long amount = row.getAmountMicros() == null ? 0 : row.getAmountMicros();
        long records = row.getRecords() == null ? 0 : row.getRecords();
        counters.deduct(row.getAgentId(), row.getActionClass(),
                LocalDate.now(ZONE), amount, records);
    }

    private void publishActionUndone(TrustActionDO row, String undoScopeId) {
        EventPublisher publisher = publishers.getIfAvailable();
        if (publisher == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "actionId", row.getId());
        put(payload, "agentId", row.getAgentId());
        // ToolCallContext 不带 orchRunId，生产 trust_action.orch_run_id 亦无写入点，
        // agentVersionId 同样可能缺省。DomainEvent 用 Map.copyOf 构造，任何 null value
        // 都会抛 NPE 并回滚整笔补偿（C-21 闸门暴露），故统一归一为空串。
        put(payload, "agentVersionId", row.getAgentVersionId());
        put(payload, "actionCategory", row.getActionClass());
        put(payload, "amountMicros", row.getAmountMicros() == null ? 0 : row.getAmountMicros());
        put(payload, "recordCount", row.getRecords() == null ? 0 : row.getRecords());
        put(payload, "orchRunId", row.getOrchRunId());
        put(payload, "workspaceId", row.getWorkspaceId());
        put(payload, "undoScopeId", undoScopeId);
        publisher.publish(new DomainEvent(
                GovernanceMqTopics.TRUST_ACTION_UNDONE,
                GovernanceMqTopics.TOPIC,
                row.getAgentId(),
                payload,
                0,
                null,
                "trust_action:" + row.getId()));
    }

    private void publishCompensationFailed(TrustActionDO row, String undoScopeId, String reason) {
        EventPublisher publisher = publishers.getIfAvailable();
        if (publisher == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "actionId", row.getId());
        put(payload, "undoScopeId", undoScopeId);
        put(payload, "reason", reason);
        put(payload, "agentId", row.getAgentId());
        put(payload, "workspaceId", row.getWorkspaceId());
        publisher.publish(new DomainEvent(
                GovernanceMqTopics.TRUST_COMPENSATION_FAILED,
                GovernanceMqTopics.TOPIC,
                row.getAgentId(),
                payload,
                0,
                null,
                "trust_action:" + row.getId()));
    }

    private ChangeSet.ApplyReceipt readReceipt(TrustActionDO row) {
        if (row.getReceiptJson() == null || row.getReceiptJson().isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(row.getReceiptJson(), ChangeSet.ApplyReceipt.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 事件 payload 不允许 null value（{@code DomainEvent} 用 Map.copyOf 构造）。 */
    private static void put(Map<String, Object> payload, String key, Object value) {
        payload.put(key, value == null ? "" : value);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** IT / 离线：不经 MQ 直接投递（handler 幂等）。 */
    public void routeLocally(Map<String, Object> payload) {
        handle(null, payload);
    }
}
