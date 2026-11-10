package com.tioka.runtime.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tioka.runtime.infrastructure.mapper.TrustUndoWindowMapper;
import com.tioka.runtime.infrastructure.persistence.TrustUndoWindowDO;
import org.springframework.stereotype.Repository;

/** {@code trust_undo_window} 读写（V202 / TRU-07）。 */
@Repository
public class TrustUndoWindowRepository {

    private final TrustUndoWindowMapper mapper;

    public TrustUndoWindowRepository(TrustUndoWindowMapper mapper) {
        this.mapper = mapper;
    }

    public Integer windowHours(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        TrustUndoWindowDO row = mapper.selectOne(new LambdaQueryWrapper<TrustUndoWindowDO>()
                .eq(TrustUndoWindowDO::getWorkspaceId, workspaceId));
        return row == null ? null : row.getWindowHours();
    }

    public void upsert(String workspaceId, int windowHours) {
        TrustUndoWindowDO row = mapper.selectOne(new LambdaQueryWrapper<TrustUndoWindowDO>()
                .eq(TrustUndoWindowDO::getWorkspaceId, workspaceId));
        if (row == null) {
            row = new TrustUndoWindowDO();
            row.setWorkspaceId(workspaceId);
            row.setWindowHours(windowHours);
            mapper.insert(row);
        } else {
            row.setWindowHours(windowHours);
            mapper.updateById(row);
        }
    }
}
