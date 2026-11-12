package com.tioka.runtime.infrastructure;

import com.tioka.runtime.infrastructure.mapper.TrustUndoCircuitMapper;
import com.tioka.runtime.infrastructure.persistence.TrustUndoCircuitDO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * {@code trust_undo_circuit} 读写（V202 / TRU-07+08，V226 增熔断开启状态）。
 * 计数窗口由 {@code windowMinutes} 决定（空间策略，缺省 60 分钟）；
 * 降级联动经 {@code tioka.trust.undo_circuit_open.v1} 交给 governance 侧 TRU-10。
 */
@Repository
public class TrustUndoCircuitRepository {

    private final TrustUndoCircuitMapper mapper;

    public TrustUndoCircuitRepository(TrustUndoCircuitMapper mapper) {
        this.mapper = mapper;
    }

    public TrustUndoCircuitDO find(String agentId) {
        return mapper.selectById(agentId);
    }

    public void insert(TrustUndoCircuitDO row) {
        mapper.insert(row);
    }

    public void update(TrustUndoCircuitDO row) {
        mapper.updateById(row);
    }

    /** 记一次撤销（窗口过期时重置计数）。 */
    public void recordUndo(String agentId, String workspaceId, LocalDateTime now, int windowMinutes) {
        record(agentId, workspaceId, now, true, windowMinutes);
    }

    public void recordUndo(String agentId, String workspaceId, LocalDateTime now) {
        recordUndo(agentId, workspaceId, now, 60);
    }

    /** 记一次补偿失败。 */
    public void recordCompensationFailure(String agentId, String workspaceId,
                                          LocalDateTime now, int windowMinutes) {
        record(agentId, workspaceId, now, false, windowMinutes);
    }

    public void recordCompensationFailure(String agentId, String workspaceId, LocalDateTime now) {
        recordCompensationFailure(agentId, workspaceId, now, 60);
    }

    /** V226：同一 Agent 同一滑动窗口只开启一次熔断；true = 本次应当发降级事件。 */
    public boolean tryOpen(String agentId, LocalDateTime windowStart, LocalDateTime now, String reason) {
        return mapper.tryOpen(agentId, windowStart, now, reason) == 1;
    }

    private void record(String agentId, String workspaceId, LocalDateTime now, boolean undo,
                        int windowMinutes) {
        TrustUndoCircuitDO row = find(agentId);
        if (row == null) {
            row = new TrustUndoCircuitDO();
            row.setAgentId(agentId);
            row.setWorkspaceId(workspaceId == null ? "" : workspaceId);
            row.setUndoCount(undo ? 1 : 0);
            row.setCompFailCount(undo ? 0 : 1);
            row.setWindowStart(now);
            insert(row);
            return;
        }
        if (row.getWindowStart() == null
                || row.getWindowStart().isBefore(now.minusMinutes(Math.max(1, windowMinutes)))) {
            row.setUndoCount(0);
            row.setCompFailCount(0);
            row.setWindowStart(now);
        }
        if (undo) {
            row.setUndoCount((row.getUndoCount() == null ? 0 : row.getUndoCount()) + 1);
        } else {
            row.setCompFailCount((row.getCompFailCount() == null ? 0 : row.getCompFailCount()) + 1);
        }
        update(row);
    }
}
