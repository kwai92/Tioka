package com.tioka.runtime.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tioka.runtime.infrastructure.mapper.TrustUndoRequestMapper;
import com.tioka.runtime.infrastructure.persistence.TrustUndoRequestDO;
import org.springframework.stereotype.Repository;

import java.util.List;

/** {@code trust_undo_request} 读写（V202 / TRU-07）。 */
@Repository
public class TrustUndoRequestRepository {

    private final TrustUndoRequestMapper mapper;

    public TrustUndoRequestRepository(TrustUndoRequestMapper mapper) {
        this.mapper = mapper;
    }

    public TrustUndoRequestDO findByScope(String scopeType, String scopeId) {
        return mapper.selectOne(new LambdaQueryWrapper<TrustUndoRequestDO>()
                .eq(TrustUndoRequestDO::getScopeType, scopeType)
                .eq(TrustUndoRequestDO::getScopeId, scopeId));
    }

    public TrustUndoRequestDO findById(String id) {
        return mapper.selectById(id);
    }

    public void insert(TrustUndoRequestDO row) {
        mapper.insert(row);
    }

    public void update(TrustUndoRequestDO row) {
        mapper.updateById(row);
    }

    public List<TrustUndoRequestDO> listByWorkspace(String workspaceId) {
        return mapper.selectList(new LambdaQueryWrapper<TrustUndoRequestDO>()
                .eq(TrustUndoRequestDO::getWorkspaceId, workspaceId)
                .orderByDesc(TrustUndoRequestDO::getId));
    }
}
