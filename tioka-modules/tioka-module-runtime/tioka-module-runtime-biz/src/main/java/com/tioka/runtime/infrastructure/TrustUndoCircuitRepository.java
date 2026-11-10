package com.tioka.runtime.infrastructure;

import com.tioka.runtime.infrastructure.mapper.TrustUndoCircuitMapper;
import com.tioka.runtime.infrastructure.persistence.TrustUndoCircuitDO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * {@code trust_undo_circuit} 读写（V202 / TRU-07+08）：撤销熔断计数（1h 滑动窗口）。
 * 本批只写计数与事件；熔断降级联动归 TRU-10（登记 M3-C4 设计笔记）。
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

    /** 记一次撤销（窗口滑动起点过期时重置计数）。 */
    public void recordUndo(String agentId, String workspaceId, LocalDateTime now) {
        record(agentId, workspaceId, now, true);
    }

    /** 记一次补偿失败。 */
    public void recordCompensationFailure(String agentId, String workspaceId, LocalDateTime now) {
        record(agentId, workspaceId, now, false);
    }

    private void record(String agentId, String workspaceId, LocalDateTime now, boolean undo) {
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
        if (row.getWindowStart() == null || row.getWindowStart().isBefore(now.minusHours(1))) {
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
