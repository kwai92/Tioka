package com.tioka.runtime.application.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.GateDecision;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolInvocation;
import com.tioka.runtime.infrastructure.TrustActionRepository;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * trust_action 读写：PROPOSED → APPROVED → APPLIED / STALE / FAILED（ADR-033）。
 */
@Component
public class PreparedCallStore {

    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_FAILED = "FAILED";

    private final TrustActionRepository actions;
    private final ObjectMapper json;

    public PreparedCallStore(TrustActionRepository actions, ObjectMapper json) {
        this.actions = actions;
        this.json = json;
    }

    @Transactional
    public TrustActionDO insertProposed(
            ToolGateway.ToolCallContext ctx,
            String agentId,
            String toolName,
            String actionClass,
            ToolRisk risk,
            Reversibility reversibility,
            GateDecision gate,
            ToolInvocation inv,
            ChangeSet changeSet,
            String itemId) {
        TrustActionDO row = new TrustActionDO();
        row.setId(Ids.newId("act"));
        String workspaceId = ctx.principal() == null ? "" : ctx.principal().workspaceId();
        row.setWorkspaceId(workspaceId == null ? "" : workspaceId);
        row.setAgentId(agentId == null ? "" : agentId);
        row.setAgentVersionId(ctx.agentVersionId());
        row.setThreadId(ctx.threadId());
        row.setTurnId(ctx.turnId());
        row.setItemId(itemId);
        row.setSeq(actions.nextSeq(ctx.turnId()));
        row.setToolName(toolName);
        row.setActionClass(actionClass == null ? toolName : actionClass);
        row.setRisk(risk == null ? "WRITE" : risk.name());
        row.setReversibility(reversibility == null ? Reversibility.IRREVERSIBLE.name() : reversibility.name());
        row.setStatus(STATUS_PROPOSED);
        row.setGate(gate == null ? GateDecision.REQUIRE_APPROVAL.name() : gate.name());
        row.setArgsJson(writeJson(inv == null ? Map.of() : inv.arguments()));
        row.setChangeSetJson(writeJson(changeSet));
        row.setFingerprint(changeSet == null ? null : changeSet.fingerprint());
        row.setEstimated(changeSet != null && changeSet.estimated());
        if (changeSet != null && changeSet.validUntil() != null) {
            row.setValidUntil(LocalDateTime.ofInstant(changeSet.validUntil(), ZoneOffset.UTC));
        }
        if (changeSet != null && changeSet.totals() != null) {
            row.setRecords(changeSet.totals().records());
            row.setAmountMicros(changeSet.totals().amountMicros());
        }
        actions.insert(row);
        return row;
    }

    @Transactional
    public TrustActionDO lockForUpdate(String preparedId) {
        TrustActionDO row = actions.findForUpdate(preparedId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "prepared call 不存在: " + preparedId);
        }
        return row;
    }

    public TrustActionDO find(String preparedId) {
        return actions.findById(preparedId);
    }

    @Transactional
    public void markApplied(TrustActionDO row, ChangeSet.ApplyReceipt receipt) {
        LocalDateTime undo = null;
        if (receipt != null && receipt.appliedAt() != null) {
            // 撤销期默认 24h；TRU-07 再做成可配
            undo = LocalDateTime.ofInstant(receipt.appliedAt().plusSeconds(24 * 3600), ZoneOffset.UTC);
        }
        int n = actions.markStatus(row.getId(), row.getStatus(), STATUS_APPLIED, writeJson(receipt), undo);
        if (n != 1) {
            throw new BizException(ErrorCode.CONFLICT, "trust_action 状态已变: " + row.getId());
        }
        row.setStatus(STATUS_APPLIED);
        row.setReceiptJson(writeJson(receipt));
        row.setUndoDeadline(undo);
    }

    @Transactional
    public void markStale(TrustActionDO row) {
        int n = actions.markStatus(row.getId(), row.getStatus(), STATUS_STALE, null, null);
        if (n != 1) {
            throw new BizException(ErrorCode.CONFLICT, "trust_action 状态已变: " + row.getId());
        }
        row.setStatus(STATUS_STALE);
    }

    @Transactional
    public void markFailed(TrustActionDO row, String reason) {
        int n = actions.markStatus(row.getId(), row.getStatus(), STATUS_FAILED,
                writeJson(Map.of("error", reason == null ? "" : reason)), null);
        if (n != 1) {
            throw new BizException(ErrorCode.CONFLICT, "trust_action 状态已变: " + row.getId());
        }
        row.setStatus(STATUS_FAILED);
    }

    @Transactional
    public void markApproved(TrustActionDO row, String approvalId) {
        row.setApprovalId(approvalId);
        int n = actions.markStatus(row.getId(), STATUS_PROPOSED, STATUS_APPROVED, null, null);
        if (n != 1) {
            throw new BizException(ErrorCode.CONFLICT, "trust_action 非 PROPOSED: " + row.getId());
        }
        row.setStatus(STATUS_APPROVED);
    }

    public boolean expired(TrustActionDO row) {
        if (row.getValidUntil() == null) {
            return false;
        }
        return Instant.now().isAfter(row.getValidUntil().toInstant(ZoneOffset.UTC));
    }

    public ChangeSet readChangeSet(TrustActionDO row) {
        try {
            return json.readValue(row.getChangeSetJson(), ChangeSet.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "change_set_json 损坏: " + row.getId());
        }
    }

    public ToolInvocation readInvocation(TrustActionDO row) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = json.readValue(row.getArgsJson(), Map.class);
            return new ToolInvocation(row.getToolName(), args == null ? Map.of() : args);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "args_json 损坏: " + row.getId());
        }
    }

    public ChangeSet.ApplyReceipt readReceipt(TrustActionDO row) {
        if (row.getReceiptJson() == null || row.getReceiptJson().isBlank()) {
            return null;
        }
        try {
            return json.readValue(row.getReceiptJson(), ChangeSet.ApplyReceipt.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "JSON 序列化失败");
        }
    }
}
