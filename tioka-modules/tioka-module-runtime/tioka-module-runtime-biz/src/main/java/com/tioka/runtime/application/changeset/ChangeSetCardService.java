package com.tioka.runtime.application.changeset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.runtime.api.approval.ApprovalQueryApi;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.GateDecision;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.runtime.infrastructure.TrustActionRepository;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
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
 * TRU-06b 变更单卡片后端（PK-25 / M2-05 第 43 行）：
 * <ul>
 *   <li>prepare（PROPOSED）后为挂起审批的变更单生成 card_json 并写 {@code runtime_approval}，
 *       同时回链 {@code trust_action.approval_id}；</li>
 *   <li>审批台详情按变更单 ID（preparedId）或审批 ID 查询变更单及当前状态
 *       （PROPOSED → APPLIED / STALE / FAILED，estimated 为预估态，见 M2-05 第 111 行）。</li>
 * </ul>
 * 状态流转本身仍由 {@code DefaultToolGateway#prepare/#commit} 驱动（PreparedCallStore）。
 */
@Service
public class ChangeSetCardService {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final TrustActionRepository actions;
    private final ApprovalRepository approvals;
    private final ChangeSetCardRenderer renderer;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;

    public ChangeSetCardService(TrustActionRepository actions,
                                ApprovalRepository approvals,
                                ChangeSetCardRenderer renderer,
                                EventPublisher publisher,
                                ObjectMapper mapper) {
        this.actions = actions;
        this.approvals = approvals;
        this.renderer = renderer;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    /**
     * 为 PROPOSED 的 prepared call 打开（或复用）变更单卡片审批，返回审批 ID（幂等）。
     */
    @Transactional
    public String openCardApproval(String workspaceId, String preparedId,
                                   Map<String, List<String>> assignee, Duration ttl, String requestedBy) {
        TrustActionDO row = actions.findById(preparedId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "prepared call 不存在: " + preparedId);
        }
        if (row.getApprovalId() != null && !row.getApprovalId().isBlank()) {
            return row.getApprovalId();
        }
        if (!GateDecision.REQUIRE_APPROVAL.name().equals(row.getGate())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "prepared call 非审批挂起（gate=" + row.getGate() + "）: " + preparedId);
        }
        ChangeSet changeSet = readChangeSet(row);
        Map<String, Object> card = renderer.render(row, changeSet);

        String approvalId = Ids.newId("apv");
        card.put("approvalId", approvalId);
        Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        String by = requestedBy == null || requestedBy.isBlank() ? "system" : requestedBy;

        RuntimeApprovalDO approval = new RuntimeApprovalDO();
        approval.setId(approvalId);
        approval.setWorkspaceId(workspaceId == null ? row.getWorkspaceId() : workspaceId);
        approval.setSubjectType("turn");
        approval.setSubjectId(row.getTurnId());
        approval.setItemId(row.getItemId());
        approval.setKind("card");
        approval.setToolName(row.getToolName());
        approval.setTitle(card.get("title") == null ? row.getToolName() : String.valueOf(card.get("title")));
        approval.setCardJson(writeJson(card));
        approval.setAssigneeJson(writeJson(Map.of(
                "userIds", assignee == null ? List.of() : assignee.getOrDefault("userIds", List.of()),
                "roleCodes", assignee == null ? List.of() : assignee.getOrDefault("roleCodes", List.of()))));
        approval.setPolicy("changeSet");
        approval.setRisk(row.getRisk());
        approval.setStatus("PENDING");
        approval.setExpiresAt(LocalDateTime.ofInstant(Instant.now().plus(effective), ZoneOffset.UTC));
        approval.setRequestedBy(by);
        approval.setCreateBy(by);
        approval.setUpdateBy(by);
        approvals.insert(approval);

        // 状态机挂钩：PROPOSED 侧回链审批（approval_id），状态仍由 prepare/commit 驱动
        actions.linkApproval(row.getId(), approvalId);

        publishRequested(approval, card);
        return approvalId;
    }

    /** 审批台详情：按变更单 ID（preparedId）查变更单及当前状态。 */
    public Optional<ApprovalQueryApi.ChangeSetDetail> detail(String preparedId) {
        TrustActionDO row = actions.findById(preparedId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toDetail(row));
    }

    /** 审批台详情：按审批 ID 查关联变更单及当前状态。 */
    public Optional<ApprovalQueryApi.ChangeSetDetail> detailByApproval(String approvalId) {
        TrustActionDO row = actions.findByApprovalId(approvalId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toDetail(row));
    }

    private ApprovalQueryApi.ChangeSetDetail toDetail(TrustActionDO row) {
        ChangeSet changeSet = readChangeSet(row);
        return new ApprovalQueryApi.ChangeSetDetail(
                row.getApprovalId(),
                row.getId(),
                row.getToolName(),
                row.getStatus(),
                row.getReversibility(),
                row.getEstimated() != null && row.getEstimated(),
                renderer.tableRows(changeSet),
                changeSet);
    }

    private void publishRequested(RuntimeApprovalDO approval, Map<String, Object> card) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approval.getId());
        payload.put("subjectType", approval.getSubjectType());
        payload.put("subjectId", approval.getSubjectId());
        payload.put("kind", approval.getKind());
        payload.put("status", approval.getStatus());
        payload.put("workspaceId", approval.getWorkspaceId() == null ? "" : approval.getWorkspaceId());
        payload.put("title", approval.getTitle() == null ? "" : approval.getTitle());
        payload.put("card", card);
        publisher.publish(new DomainEvent(
                RuntimeMqTopics.APPROVAL_REQUESTED,
                RuntimeMqTopics.TOPIC,
                approval.getId(),
                payload,
                0,
                null,
                "approval:" + approval.getId()));
    }

    private ChangeSet readChangeSet(TrustActionDO row) {
        if (row.getChangeSetJson() == null || row.getChangeSetJson().isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(row.getChangeSetJson(), ChangeSet.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "change_set_json 损坏: " + row.getId());
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
