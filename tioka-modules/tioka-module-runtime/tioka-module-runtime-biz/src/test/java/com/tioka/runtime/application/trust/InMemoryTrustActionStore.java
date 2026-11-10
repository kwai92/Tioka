package com.tioka.runtime.application.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolInvocation;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.testkit.FakeErp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** TRU-07 离线测试支撑：内存 trust_action 存取（CAS 状态机语义与 SQL 一致）。 */
final class InMemoryTrustActionStore implements TrustActionStore {

    final Map<String, TrustActionDO> rows = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    InMemoryTrustActionStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void seedApplied(FakeErp erp, String id, String turnId, String orchRunId, int seq,
                     String toolName, String actionClass, Reversibility reversibility,
                     ToolInvocation inv, String workspaceId) {
        ChangeSet changeSet = erp.preview(inv);
        ChangeSet.ApplyReceipt receipt = erp.apply(inv, changeSet, changeSet.fingerprint());
        TrustActionDO row = new TrustActionDO();
        row.setId(id);
        row.setWorkspaceId(workspaceId);
        row.setAgentId("agt_1");
        row.setAgentVersionId("av_1");
        row.setThreadId("thr_1");
        row.setTurnId(turnId);
        row.setOrchRunId(orchRunId);
        row.setItemId("itm_" + seq);
        row.setSeq(seq);
        row.setToolName(toolName);
        row.setActionClass(actionClass);
        row.setRisk("WRITE");
        row.setReversibility(reversibility.name());
        row.setStatus("APPLIED");
        row.setGate("AUTO_COMMIT");
        row.setArgsJson(writeJson(inv.arguments()));
        row.setChangeSetJson(writeJson(changeSet));
        row.setEstimated(false);
        row.setRecords(1);
        row.setAmountMicros(1_000_000L);
        row.setAutonomyLevel("L2");
        row.setUndoAttempts(0);
        row.setUndoDeadline(LocalDateTime.ofInstant(Instant.now().plusSeconds(24 * 3600), ZoneOffset.UTC));
        try {
            row.setReceiptJson(mapper.writeValueAsString(receipt));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        rows.put(id, row);
    }

    private String writeJson(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public TrustActionDO findById(String id) {
        return rows.get(id);
    }

    @Override
    public TrustActionDO findByApprovalId(String approvalId) {
        return rows.values().stream()
                .filter(r -> approvalId.equals(r.getApprovalId())).findFirst().orElse(null);
    }

    @Override
    public void linkApproval(String id, String approvalId) {
        rows.get(id).setApprovalId(approvalId);
    }

    @Override
    public TrustActionDO findForUpdate(String id) {
        return rows.get(id);
    }

    @Override
    public void insert(TrustActionDO row) {
        rows.put(row.getId(), row);
    }

    @Override
    public int markStatus(String id, String fromStatus, String toStatus,
                          String receiptJson, LocalDateTime undoDeadline) {
        TrustActionDO row = rows.get(id);
        if (row == null || !fromStatus.equals(row.getStatus())) {
            return 0;
        }
        row.setStatus(toStatus);
        if (receiptJson != null) {
            row.setReceiptJson(receiptJson);
        }
        if (undoDeadline != null) {
            row.setUndoDeadline(undoDeadline);
        }
        return 1;
    }

    @Override
    public int nextSeq(String turnId) {
        int max = 0;
        for (TrustActionDO r : rows.values()) {
            if (turnId.equals(r.getTurnId()) && r.getSeq() != null) {
                max = Math.max(max, r.getSeq());
            }
        }
        return max + 1;
    }

    @Override
    public List<TrustActionDO> listAppliedInDay(String agentId, String actionClass,
                                                LocalDateTime fromUtc, LocalDateTime toUtc) {
        List<TrustActionDO> out = new ArrayList<>();
        for (TrustActionDO r : rows.values()) {
            if ("APPLIED".equals(r.getStatus()) && r.getUndoneBy() == null) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public List<TrustActionDO> listAppliedInScope(String scopeType, String scopeId) {
        List<TrustActionDO> out = new ArrayList<>();
        for (TrustActionDO r : rows.values()) {
            if (inScope(r, scopeType, scopeId)) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Integer.compare(b.getSeq(), a.getSeq()));
        return out;
    }

    @Override
    public List<TrustActionDO> listCompensatingInScope(String scopeType, String scopeId) {
        List<TrustActionDO> out = new ArrayList<>();
        for (TrustActionDO r : rows.values()) {
            if ("COMPENSATING".equals(r.getStatus()) && inScope(r, scopeType, scopeId)) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Integer.compare(a.getSeq(), b.getSeq()));
        return out;
    }

    private static boolean inScope(TrustActionDO r, String scopeType, String scopeId) {
        return switch (scopeType) {
            case "turn" -> scopeId.equals(r.getTurnId());
            case "run" -> scopeId.equals(r.getOrchRunId());
            case "action" -> scopeId.equals(r.getId());
            default -> false;
        };
    }

    @Override
    public void markCompensatingMeta(String id, String scopeType, String scopeId, int seq) {
        TrustActionDO row = rows.get(id);
        row.setUndoScopeType(scopeType);
        row.setUndoScopeId(scopeId);
        row.setCompensateSeq(seq);
    }

    @Override
    public int bumpUndoAttempts(String id) {
        TrustActionDO row = rows.get(id);
        row.setUndoAttempts((row.getUndoAttempts() == null ? 0 : row.getUndoAttempts()) + 1);
        return row.getUndoAttempts();
    }

    @Override
    public int countCompensatedInWindow(String agentId, LocalDateTime fromUtc, LocalDateTime toUtc) {
        return (int) rows.values().stream()
                .filter(r -> "COMPENSATED".equals(r.getStatus()))
                .count();
    }
}
