package com.tioka.runtime.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tioka.runtime.application.trust.UndoCircuitPolicy;
import com.tioka.runtime.infrastructure.mapper.TrustUndoCircuitPolicyMapper;
import com.tioka.runtime.infrastructure.persistence.TrustUndoCircuitPolicyDO;
import org.springframework.stereotype.Repository;

/** {@code trust_undo_circuit_policy} 读写（V226 / TRU-08）。无配置行时返回规格缺省值。 */
@Repository
public class TrustUndoCircuitPolicyRepository {

    private final TrustUndoCircuitPolicyMapper mapper;

    public TrustUndoCircuitPolicyRepository(TrustUndoCircuitPolicyMapper mapper) {
        this.mapper = mapper;
    }

    public UndoCircuitPolicy find(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return UndoCircuitPolicy.defaults("");
        }
        TrustUndoCircuitPolicyDO row = mapper.selectOne(
                new LambdaQueryWrapper<TrustUndoCircuitPolicyDO>()
                        .eq(TrustUndoCircuitPolicyDO::getWorkspaceId, workspaceId));
        if (row == null) {
            return UndoCircuitPolicy.defaults(workspaceId);
        }
        return new UndoCircuitPolicy(workspaceId,
                row.getUndoThreshold() == null ? UndoCircuitPolicy.DEFAULT_UNDO_THRESHOLD : row.getUndoThreshold(),
                row.getCompFailThreshold() == null ? UndoCircuitPolicy.DEFAULT_COMP_FAIL_THRESHOLD : row.getCompFailThreshold(),
                row.getWindowMinutes() == null ? UndoCircuitPolicy.DEFAULT_WINDOW_MINUTES : row.getWindowMinutes());
    }

    public void upsert(UndoCircuitPolicy policy, String actor) {
        TrustUndoCircuitPolicyDO row = mapper.selectOne(
                new LambdaQueryWrapper<TrustUndoCircuitPolicyDO>()
                        .eq(TrustUndoCircuitPolicyDO::getWorkspaceId, policy.workspaceId()));
        boolean insert = row == null;
        if (insert) {
            row = new TrustUndoCircuitPolicyDO();
            row.setWorkspaceId(policy.workspaceId());
        }
        row.setUndoThreshold(policy.undoThreshold());
        row.setCompFailThreshold(policy.compFailThreshold());
        row.setWindowMinutes(policy.windowMinutes());
        row.setUpdateBy(actor == null ? "system" : actor);
        if (insert) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }
}
