package com.tioka.runtime.application.trust;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.runtime.infrastructure.TrustUndoCircuitPolicyRepository;
import com.tioka.runtime.infrastructure.TrustUndoCircuitRepository;
import com.tioka.runtime.infrastructure.persistence.TrustUndoCircuitDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TRU-08 撤销熔断判定（M3-05 §6）：同一 Agent 在滑动窗口内撤销次数或补偿失败次数达到
 * 空间阈值时，开启熔断并发布 {@code tioka.trust.undo_circuit_open.v1}，由 governance 侧
 * TRU-10 消费者把全部动作类别降到 L1 并通知负责人。
 *
 * <p>为什么发事件而不是直调降级：{@code LayeredDependencyArchTest} 禁止 runtime-biz 依赖
 * governance-biz；治理侧也不读 runtime 的 {@code trust_action}（M3-C4 设计笔记）。
 * 同一窗口只发一次由 V226 的 {@code trust_undo_circuit.opened_at} CAS 保证。
 */
@Service
public class UndoCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(UndoCircuitBreaker.class);

    public static final String REASON_UNDO_THRESHOLD = "UNDO_THRESHOLD";
    public static final String REASON_COMPENSATION_FAILURE = "COMPENSATION_FAILURE";

    private final TrustUndoCircuitRepository circuit;
    private final TrustUndoCircuitPolicyRepository policies;
    private final ObjectProvider<EventPublisher> publishers;
    private final ObjectProvider<AuditApi> auditApi;

    public UndoCircuitBreaker(TrustUndoCircuitRepository circuit,
                              TrustUndoCircuitPolicyRepository policies,
                              ObjectProvider<EventPublisher> publishers,
                              ObjectProvider<AuditApi> auditApi) {
        this.circuit = circuit;
        this.policies = policies;
        this.publishers = publishers;
        this.auditApi = auditApi;
    }

    /** 计数写入前读取空间窗口（保证计数与阈值判定用的是同一个窗口口径）。 */
    public int windowMinutes(String workspaceId) {
        return policies.find(workspaceId).windowMinutes();
    }

    /**
     * 空间策略配置（阈值可配）；每次变化写审计（M3-05 §6「配置变化写审计」）。
     */
    public UndoCircuitPolicy configure(String workspaceId, int undoThreshold,
                                       int compFailThreshold, int windowMinutes, String actor) {
        UndoCircuitPolicy policy = new UndoCircuitPolicy(workspaceId, undoThreshold,
                compFailThreshold, windowMinutes);
        policies.upsert(policy, actor);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceId", workspaceId);
        details.put("undoThreshold", undoThreshold);
        details.put("compFailThreshold", compFailThreshold);
        details.put("windowMinutes", windowMinutes);
        details.put("actor", actor == null ? "system" : actor);
        audit("trust.undo.circuit_policy_changed", workspaceId, details);
        return policy;
    }

    /**
     * 撤销计数后的熔断判定。补偿失败不走这里：TRU-10 直接消费
     * {@code tioka.trust.compensation_failed.v1} 立即全类别降 L1（M3-05 §8），
     * 不需要等计数阈值；此处仍累计 comp_fail_count 并纳入判定，口径等价。
     */
    public void onUndo(String agentId, String workspaceId, String scopeId, LocalDateTime now) {
        evaluate(agentId, workspaceId, scopeId, now);
    }

    private void evaluate(String agentId, String workspaceId, String scopeId, LocalDateTime now) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        UndoCircuitPolicy policy = policies.find(workspaceId);
        TrustUndoCircuitDO row = circuit.find(agentId);
        if (row == null || row.getWindowStart() == null) {
            return;
        }
        // 计数窗口已滚动到下一窗口时不判定（避免用旧窗口计数误触发）
        if (row.getWindowStart().isBefore(now.minusMinutes(policy.windowMinutes()))) {
            return;
        }
        int undoCount = row.getUndoCount() == null ? 0 : row.getUndoCount();
        int failCount = row.getCompFailCount() == null ? 0 : row.getCompFailCount();
        boolean undoHit = undoCount >= policy.undoThreshold();
        boolean failHit = failCount >= policy.compFailThreshold();
        if (!undoHit && !failHit) {
            return;
        }
        String reason = failHit ? REASON_COMPENSATION_FAILURE : REASON_UNDO_THRESHOLD;
        LocalDateTime windowStart = row.getWindowStart();
        // 先确认发布器可用再消耗 CAS：否则 opened_at 被写成功、事件却没发出去，
        // 同窗口后续 tryOpen 全部返回 false，降级永久静默失效。
        EventPublisher publisher = publishers.getIfAvailable();
        if (publisher == null) {
            throw new IllegalStateException("熔断降级事件发布器缺席，拒绝开启熔断");
        }
        if (!circuit.tryOpen(agentId, windowStart, now, reason)) {
            log.debug("熔断已在窗口内开启，跳过重复事件 agentId={} windowStart={}", agentId, windowStart);
            return;
        }
        publish(publisher, agentId, workspaceId, scopeId, reason, policy,
                windowStart, undoCount, failCount);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("agentId", agentId);
        details.put("reason", reason);
        details.put("undoCount", undoCount);
        details.put("compFailCount", failCount);
        details.put("windowStart", windowStart.toString());
        audit("trust.undo.circuit_open", agentId, details);
    }

    private void publish(EventPublisher publisher, String agentId, String workspaceId,
                         String scopeId, String reason, UndoCircuitPolicy policy,
                         LocalDateTime windowStart, int undoCount, int failCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agentId);
        payload.put("workspaceId", workspaceId == null ? "" : workspaceId);
        payload.put("reason", reason);
        payload.put("undoCount", undoCount);
        payload.put("compFailCount", failCount);
        payload.put("windowStart", windowStart.atOffset(ZoneOffset.UTC).toString());
        payload.put("undoThreshold", policy.undoThreshold());
        payload.put("compFailThreshold", policy.compFailThreshold());
        payload.put("windowMinutes", policy.windowMinutes());
        payload.put("undoScopeId", scopeId == null ? "" : scopeId);
        publisher.publish(new DomainEvent(
                GovernanceMqTopics.TRUST_UNDO_CIRCUIT_OPEN,
                GovernanceMqTopics.TOPIC,
                agentId,
                payload,
                0,
                null,
                "trust_undo_circuit:" + agentId + ":" + windowStart));
    }

    private void audit(String action, String resourceId, Map<String, Object> details) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        try {
            api.recordIndependent(AuditEvent.builder()
                    .ts(Instant.now())
                    .action(action)
                    .resourceType("trust_undo_circuit")
                    .resourceId(resourceId)
                    .outcome(AuditOutcome.SUCCESS)
                    .details(details)
                    .build());
        } catch (RuntimeException e) {
            log.warn("熔断审计写入失败: {}", e.toString());
        }
    }
}
