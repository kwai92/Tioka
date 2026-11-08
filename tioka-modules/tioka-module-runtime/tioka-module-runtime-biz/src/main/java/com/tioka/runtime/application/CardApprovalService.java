package com.tioka.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.approval.CardApprovalApi;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ApprovalKind;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 卡片人审创建（ORC-04 / FLW-03）。放在 application 包，不改 {@code kernel/approval} 决定路径（K2）。
 */
@Service
public class CardApprovalService implements CardApprovalApi {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final ApprovalRepository approvals;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;

    public CardApprovalService(ApprovalRepository approvals,
                               EventPublisher publisher,
                               ObjectMapper mapper) {
        this.approvals = approvals;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ApprovalView openOrReuse(OpenCardCommand cmd) {
        Optional<ApprovalView> existing = findPending(cmd.subjectType(), cmd.subjectId(), cmd.key());
        if (existing.isPresent()) {
            return existing.get();
        }
        Duration ttl = cmd.timeout() == null || cmd.timeout().isZero() || cmd.timeout().isNegative()
                ? DEFAULT_TTL : cmd.timeout();
        String approvalId = Ids.newId("apv");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("kind", "card");
        card.put("key", cmd.key());
        card.put("title", cmd.title() == null ? "" : cmd.title());
        card.put("summary", cmd.summary() == null ? "" : cmd.summary());
        card.put("form", cmd.form() == null ? List.of() : cmd.form());
        if (cmd.onTimeout() != null && !cmd.onTimeout().isEmpty()) {
            card.put("onTimeout", cmd.onTimeout());
        }
        Map<String, List<String>> assignee = cmd.assignee() == null ? Map.of() : cmd.assignee();
        Map<String, Object> assigneePayload = new LinkedHashMap<>();
        assigneePayload.put("userIds", assignee.getOrDefault("userIds", List.of()));
        assigneePayload.put("roleCodes", assignee.getOrDefault("roleCodes",
                assignee.getOrDefault("roles", List.of())));

        RuntimeApprovalDO row = new RuntimeApprovalDO();
        row.setId(approvalId);
        row.setWorkspaceId(cmd.workspaceId());
        row.setSubjectType(cmd.subjectType());
        row.setSubjectId(cmd.subjectId());
        row.setKind("card");
        row.setToolName(cmd.title());
        row.setTitle(cmd.title());
        row.setCardJson(json(card));
        row.setAssigneeJson(json(assigneePayload));
        row.setPolicy("card");
        row.setStatus(ApprovalStatus.PENDING.name());
        row.setExpiresAt(LocalDateTime.ofInstant(Instant.now().plus(ttl), ZoneOffset.UTC));
        String by = cmd.requestedBy() == null || cmd.requestedBy().isBlank() ? "system" : cmd.requestedBy();
        row.setRequestedBy(by);
        row.setCreateBy(by);
        row.setUpdateBy(by);
        approvals.insert(row);

        ApprovalView view = toView(row);
        publishRequested(row, view);
        return view;
    }

    @Override
    public Optional<ApprovalView> findPending(String subjectType, String subjectId, String key) {
        if (subjectType == null || subjectId == null || key == null) {
            return Optional.empty();
        }
        return approvals.listBySubject(subjectType, subjectId).stream()
                .filter(a -> ApprovalStatus.PENDING.name().equals(a.getStatus()))
                .filter(a -> "card".equalsIgnoreCase(a.getKind()))
                .filter(a -> key.equals(cardKey(a.getCardJson())))
                .findFirst()
                .map(this::toView);
    }

    private void publishRequested(RuntimeApprovalDO a, ApprovalView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", a.getId());
        payload.put("subjectType", a.getSubjectType());
        payload.put("subjectId", a.getSubjectId());
        payload.put("kind", a.getKind());
        payload.put("status", a.getStatus());
        payload.put("workspaceId", a.getWorkspaceId() == null ? "" : a.getWorkspaceId());
        payload.put("title", a.getTitle() == null ? "" : a.getTitle());
        payload.put("card", view.card() == null ? Map.of() : view.card());
        publisher.publish(new DomainEvent(
                RuntimeMqTopics.APPROVAL_REQUESTED,
                RuntimeMqTopics.TOPIC,
                a.getId(),
                payload,
                0,
                null,
                "approval:" + a.getId()));
    }

    private ApprovalView toView(RuntimeApprovalDO a) {
        Map<String, Object> assignee = parseMap(a.getAssigneeJson());
        @SuppressWarnings("unchecked")
        List<String> userIds = assignee.get("userIds") instanceof List<?> l ? (List<String>) l : List.of();
        @SuppressWarnings("unchecked")
        List<String> roles = assignee.get("roleCodes") instanceof List<?> l ? (List<String>) l : List.of();
        String title = a.getTitle() != null ? a.getTitle() : a.getToolName();
        return new ApprovalView(
                a.getId(),
                parseSubjectType(a.getSubjectType()),
                a.getSubjectId(),
                ApprovalKind.CARD,
                title,
                null,
                null,
                Map.of(),
                parseMap(a.getCardJson()),
                ToolRisk.WRITE,
                ApprovalStatus.valueOf(a.getStatus()),
                a.getWorkspaceId(),
                a.getRequestedBy() != null ? a.getRequestedBy() : a.getCreateBy(),
                userIds,
                roles,
                a.getDecidedBy(),
                a.getDecidedAt() == null ? null : a.getDecidedAt().toInstant(ZoneOffset.UTC),
                parseMap(a.getAnswerJson()),
                null,
                a.getExpiresAt() == null ? null : a.getExpiresAt().toInstant(ZoneOffset.UTC),
                a.getCreateAt() == null ? null : a.getCreateAt().toInstant(ZoneOffset.UTC),
                null);
    }

    private String cardKey(String cardJson) {
        Object key = parseMap(cardJson).get("key");
        return key == null ? null : String.valueOf(key);
    }

    private static ApprovalSubjectType parseSubjectType(String wire) {
        for (ApprovalSubjectType t : ApprovalSubjectType.values()) {
            if (t.wire().equalsIgnoreCase(wire) || t.name().equalsIgnoreCase(wire)) {
                return t;
            }
        }
        return ApprovalSubjectType.ORCH_RUN;
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
