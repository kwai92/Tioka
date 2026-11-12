package com.tioka.runtime.application.trust;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.runtime.application.gateway.PreparedCallStore;

import com.tioka.runtime.infrastructure.TrustUndoCircuitRepository;
import com.tioka.runtime.infrastructure.TrustUndoRequestRepository;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.runtime.infrastructure.persistence.TrustUndoRequestDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 撤销服务（TRU-07 / ADR-033「撤销」）。
 *
 * <pre>
 * POST /api/trust/undo {scope, id}
 *   事务内：选出范围内 APPLIED 且未过撤销期的 trust_action，按 seq 倒序
 *           REVERSIBLE / COMPENSABLE → COMPENSATING + outbox tioka.trust.compensate.v1（顺序号）
 *           IRREVERSIBLE → 「无法撤销」清单
 *   返回：将补偿的动作、无法撤销的动作
 * </pre>
 *
 * <p>同范围撤销经 {@code trust_undo_request} UNIQUE(scope_type, scope_id) 串行：
 * 第二次撤销返回第一次结果（幂等）。补偿消息按撤销范围 ID 作分区键（subjectKey），
 * 保证同组顺序（ADR-033）。补偿失败的人审由消费者生成；本服务对「无法撤销」清单
 * 生成负责人 Attention（ADR-033：不静默）。
 */
@Service
public class UndoService {

    private static final Logger log = LoggerFactory.getLogger(UndoService.class);
    private static final Set<String> SCOPES = Set.of("turn", "run", "action");
    private static final Set<String> COMPENSABLE = Set.of("REVERSIBLE", "COMPENSABLE");

    private final TrustActionStore actions;
    private final TrustUndoRequestRepository requests;
    private final TrustUndoCircuitRepository circuit;
    private final PreparedCallStore preparedCalls;
    private final EventPublisher publisher;
    private final UndoAuthorizer authorizer;
    private final ObjectProvider<AuditApi> auditApi;
    private final ObjectMapper mapper;
    /** TRU-08 熔断判定（C-21）；离线单测用旧构造器置空，行为 = C-4 时期。 */
    private final UndoCircuitBreaker breaker;

    /** 兼容离线单测 / 无熔断联动装配。 */
    public UndoService(TrustActionStore actions,
                       TrustUndoRequestRepository requests,
                       TrustUndoCircuitRepository circuit,
                       PreparedCallStore preparedCalls,
                       EventPublisher publisher,
                       UndoAuthorizer authorizer,
                       ObjectProvider<AuditApi> auditApi,
                       ObjectMapper mapper) {
        this(actions, requests, circuit, preparedCalls, publisher, authorizer, auditApi, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UndoService(TrustActionStore actions,
                       TrustUndoRequestRepository requests,
                       TrustUndoCircuitRepository circuit,
                       PreparedCallStore preparedCalls,
                       EventPublisher publisher,
                       UndoAuthorizer authorizer,
                       ObjectProvider<AuditApi> auditApi,
                       ObjectMapper mapper,
                       UndoCircuitBreaker breaker) {
        this.actions = actions;
        this.requests = requests;
        this.circuit = circuit;
        this.preparedCalls = preparedCalls;
        this.publisher = publisher;
        this.authorizer = authorizer;
        this.auditApi = auditApi;
        this.mapper = mapper;
        this.breaker = breaker;
    }

    @Transactional
    public UndoResult undo(String scopeType, String scopeId, Principal principal) {
        String scope = scopeType == null ? "" : scopeType.trim().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope) || scopeId == null || scopeId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "撤销范围须为 turn|run|action 且带 ID");
        }
        // 幂等：同范围第二次撤销返回第一次结果（DB 唯一约束兜底并发）
        TrustUndoRequestDO existing = requests.findByScope(scope, scopeId);
        if (existing != null) {
            return toResult(existing);
        }

        List<TrustActionDO> rows = actions.listAppliedInScope(scope, scopeId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "范围内没有可撤销的已提交动作: " + scopeId);
        }
        authorizer.check(principal, scopeId, rows);

        // APPLIED 且未过撤销期；过期动作不给补偿也不给清单时返回 UNDO_WINDOW_CLOSED
        List<TrustActionDO> applied = rows.stream()
                .filter(r -> PreparedCallStore.STATUS_APPLIED.equals(r.getStatus())).toList();
        Instant now = Instant.now();
        List<TrustActionDO> eligible = new ArrayList<>();
        boolean anyExpired = false;
        for (TrustActionDO row : applied) {
            if (row.getUndoDeadline() == null || row.getUndoDeadline().toInstant(ZoneOffset.UTC).isAfter(now)) {
                eligible.add(row);
            } else {
                anyExpired = true;
            }
        }
        if (eligible.isEmpty()) {
            throw new BizException(ErrorCode.UNDO_WINDOW_CLOSED,
                    "撤销期已过: " + scopeId + (anyExpired ? "" : "（范围内无已提交动作）"));
        }

        TrustUndoRequestDO request = new TrustUndoRequestDO();
        request.setId(Ids.newId("undo"));
        request.setWorkspaceId(rows.get(0).getWorkspaceId());
        request.setScopeType(scope);
        request.setScopeId(scopeId);
        request.setRequester(principal == null ? "system" : principal.userId());
        request.setStatus("OPEN");
        try {
            requests.insert(request);
        } catch (RuntimeException e) {
            // 并发第二次请求撞唯一约束 → 返回第一次结果
            TrustUndoRequestDO winner = requests.findByScope(scope, scopeId);
            if (winner != null) {
                return toResult(winner);
            }
            throw e;
        }

        List<Map<String, Object>> compensating = new ArrayList<>();
        List<Map<String, Object>> irreversible = new ArrayList<>();
        int order = 0;
        for (TrustActionDO row : eligible) { // 已按 seq 倒序
            Map<String, Object> ref = actionRef(row);
            if (COMPENSABLE.contains(row.getReversibility())) {
                order++;
                preparedCalls.markCompensating(row.getId(), scope, scopeId, order);
                publishCompensate(request, row, order);
                compensating.add(ref);
            } else {
                irreversible.add(ref);
            }
        }

        request.setCompensatedJson(writeJson(compensating));
        request.setIrreversibleJson(writeJson(irreversible));
        requests.update(request);

        // 熔断计数（TRU-07/08 共用）；C-21 起按空间窗口计数并触发降级事件（TRU-10 消费）
        TrustActionDO first = eligible.get(0);
        LocalDateTime stamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        try {
            int windowMinutes = breaker == null ? 60 : breaker.windowMinutes(first.getWorkspaceId());
            circuit.recordUndo(first.getAgentId(), first.getWorkspaceId(), stamp, windowMinutes);
        } catch (RuntimeException e) {
            // 仅计数写入保留 C-4 既有容错（不阻断撤销）
            log.warn("撤销熔断计数写入失败: {}", e.toString());
        }
        if (breaker != null) {
            // 判定 / 事件发布失败必须让撤销事务整体回滚：否则 opened_at 已被 CAS
            // 消耗、降级事件却没发出去，该窗口的熔断会永久静默失效。
            breaker.onUndo(first.getAgentId(), first.getWorkspaceId(), scopeId, stamp);
        }

        auditUndo(request, compensating, irreversible, principal);
        publishUndoRecorded(request, compensating.size(), irreversible.size());
        return new UndoResult(request.getId(), "OPEN", compensating, irreversible, null);
    }

    /**
     * EVO-01：撤销登记事件（与撤销同事务 outbox）。eval-biz 消费后对 turn 范围撤销
     * 自动写一条负向反馈（M3-06 §4.1；依赖方向：runtime 不反向依赖 eval，经事件解耦）。
     */
    private void publishUndoRecorded(TrustUndoRequestDO request, int compensating, int irreversible) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("undoId", request.getId());
        payload.put("scopeType", request.getScopeType());
        payload.put("scopeId", request.getScopeId());
        payload.put("requester", request.getRequester());
        payload.put("workspaceId", request.getWorkspaceId());
        payload.put("compensating", compensating);
        payload.put("irreversible", irreversible);
        publisher.publish(new DomainEvent(
                GovernanceMqTopics.TRUST_UNDO_RECORDED,
                GovernanceMqTopics.TOPIC,
                request.getScopeId(),
                payload,
                0,
                null,
                "trust_undo_request:" + request.getId()));
    }

    private void publishCompensate(TrustUndoRequestDO request, TrustActionDO row, int order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionId", row.getId());
        payload.put("undoScopeId", request.getScopeId());
        payload.put("seq", order);
        payload.put("toolName", row.getToolName());
        payload.put("workspaceId", row.getWorkspaceId());
        // subjectKey = 撤销范围 ID：RocketMQ 分区键，同组顺序消费（ADR-033）
        publisher.publish(new DomainEvent(
                GovernanceMqTopics.TRUST_COMPENSATE,
                GovernanceMqTopics.TOPIC,
                request.getScopeId(),
                payload,
                0,
                null,
                "trust_action:" + row.getId()));
    }

    private static Map<String, Object> actionRef(TrustActionDO row) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("actionId", row.getId());
        ref.put("toolName", row.getToolName());
        ref.put("actionClass", row.getActionClass());
        ref.put("seq", row.getSeq());
        ref.put("reversibility", row.getReversibility());
        return ref;
    }

    @SuppressWarnings("unchecked")
    private UndoResult toResult(TrustUndoRequestDO row) {
        return new UndoResult(row.getId(), row.getStatus(),
                readList(row.getCompensatedJson()), readList(row.getIrreversibleJson()),
                row.getFailedActionId());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private void auditUndo(TrustUndoRequestDO request, List<Map<String, Object>> compensating,
                           List<Map<String, Object>> irreversible, Principal principal) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scopeType", request.getScopeType());
        details.put("scopeId", request.getScopeId());
        details.put("undoId", request.getId());
        details.put("compensating", compensating.size());
        details.put("irreversible", irreversible.size());
        AuditEvent.Builder b = AuditEvent.builder()
                .ts(Instant.now())
                .action("trust.undo")
                .resourceType("trust_undo_request")
                .resourceId(request.getId())
                .outcome(AuditOutcome.SUCCESS)
                .details(details);
        if (principal != null) {
            b.workspaceId(principal.workspaceId())
                    .actorType(principal.type().name().toLowerCase(Locale.ROOT))
                    .actorId(principal.userId());
        }
        try {
            api.recordIndependent(b.build());
        } catch (RuntimeException e) {
            log.warn("trust.undo 审计写入失败: {}", e.toString());
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /** 撤销结果：将补偿清单与无法撤销清单（ADR-033）。 */
    public record UndoResult(String undoId, String status,
                             List<Map<String, Object>> compensating,
                             List<Map<String, Object>> irreversible,
                             String failedActionId) {}
}
