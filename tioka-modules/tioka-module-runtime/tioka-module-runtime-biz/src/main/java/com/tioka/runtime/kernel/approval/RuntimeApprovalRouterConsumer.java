package com.tioka.runtime.kernel.approval;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.runtime.api.approval.ApprovalSubjectHandler;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.kernel.ApprovalKind;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消费 {@code tioka.runtime.approval_decided.v1}，按 subjectType 调用 {@link ApprovalSubjectHandler}（ORC-02 / PK-03）。
 */
@Component
public class RuntimeApprovalRouterConsumer extends IdempotentConsumer<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(RuntimeApprovalRouterConsumer.class);

    private final ApprovalRepository approvals;
    private final Map<String, ApprovalSubjectHandler> handlers;

    public RuntimeApprovalRouterConsumer(ApprovalRepository approvals,
                                         List<ApprovalSubjectHandler> handlerList) {
        this.approvals = approvals;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(h -> h.subjectType().toLowerCase(Locale.ROOT),
                        Function.identity(), (a, b) -> a));
    }

    @Override
    protected String consumerName() {
        return "runtime-approval-router";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return RuntimeMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return RuntimeMqTopics.APPROVAL_DECIDED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String approvalId = stringOf(payload.get("approvalId"));
        ApprovalView view = null;
        if (approvalId != null && !approvalId.isBlank()) {
            view = approvals.findById(approvalId).map(this::toView).orElse(null);
        }
        if (view == null) {
            view = viewFromPayload(payload);
        }
        if (view == null) {
            log.warn("approval_decided 无法解析主体 eventId={}", envelope.eventId());
            return;
        }
        String typeKey = stringOf(payload.get("subjectType"));
        if (typeKey == null || typeKey.isBlank()) {
            typeKey = view.subjectType() == null ? null : view.subjectType().wire();
        }
        if (typeKey == null) {
            log.warn("approval_decided 无 subjectType eventId={}", envelope.eventId());
            return;
        }
        ApprovalSubjectHandler h = handlers.get(typeKey.toLowerCase(Locale.ROOT));
        if (h == null) {
            log.debug("无 ApprovalSubjectHandler subjectType={}", typeKey);
            return;
        }
        h.onDecided(view);
    }

    /** 同进程即时路由（IT/local 默认关 Relay 时，由 decide afterCommit 调用；handler 须幂等）。 */
    public void routeLocally(ApprovalView view) {
        if (view == null || view.subjectType() == null) {
            return;
        }
        ApprovalSubjectHandler h = handlers.get(view.subjectType().wire().toLowerCase(Locale.ROOT));
        if (h != null) {
            h.onDecided(view);
        }
    }

    private ApprovalView toView(RuntimeApprovalDO a) {
        // 轻量视图：handler 当前主要用 subjectId / status；其余从 DO 填齐契约字段
        return new ApprovalView(
                a.getId(),
                parseSubjectType(a.getSubjectType()),
                a.getSubjectId(),
                "card".equalsIgnoreCase(a.getKind()) ? ApprovalKind.CARD : ApprovalKind.TOOL,
                a.getToolName(),
                null,
                a.getToolName(),
                Map.of(),
                Map.of(),
                null,
                ApprovalStatus.valueOf(a.getStatus()),
                a.getWorkspaceId(),
                a.getCreateBy(),
                List.of(),
                List.of(),
                a.getDecidedBy(),
                a.getDecidedAt() == null ? null : a.getDecidedAt().toInstant(java.time.ZoneOffset.UTC),
                Map.of(),
                null,
                a.getExpiresAt() == null ? null : a.getExpiresAt().toInstant(java.time.ZoneOffset.UTC),
                a.getCreateAt() == null ? null : a.getCreateAt().toInstant(java.time.ZoneOffset.UTC),
                null);
    }

    private static ApprovalView viewFromPayload(Map<String, Object> payload) {
        String subjectId = stringOf(payload.get("subjectId"));
        String subjectType = stringOf(payload.get("subjectType"));
        String status = stringOf(payload.get("status"));
        if (subjectId == null || subjectType == null || status == null) {
            return null;
        }
        ApprovalStatus st;
        try {
            st = ApprovalStatus.valueOf(status);
        } catch (Exception e) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = payload.get("answer") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return new ApprovalView(
                stringOf(payload.get("approvalId")),
                parseSubjectType(subjectType),
                subjectId,
                ApprovalKind.TOOL,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                st,
                stringOf(payload.get("workspaceId")),
                null,
                List.of(),
                List.of(),
                stringOf(payload.get("decidedBy")),
                parseInstant(payload.get("decidedAt")),
                answer,
                stringOf(payload.get("comment")),
                null,
                null,
                null);
    }

    private static ApprovalSubjectType parseSubjectType(String wire) {
        for (ApprovalSubjectType t : ApprovalSubjectType.values()) {
            if (t.wire().equalsIgnoreCase(wire) || t.name().equalsIgnoreCase(wire)) {
                return t;
            }
        }
        return ApprovalSubjectType.TURN;
    }

    private static Instant parseInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
