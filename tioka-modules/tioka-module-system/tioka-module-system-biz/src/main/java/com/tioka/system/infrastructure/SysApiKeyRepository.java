package com.tioka.system.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.system.infrastructure.mapper.SysApiKeyMapper;
import com.tioka.system.infrastructure.persistence.SysApiKeyDO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class SysApiKeyRepository {
    private final SysApiKeyMapper mapper;

    public SysApiKeyRepository(SysApiKeyMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(SysApiKeyDO row) {
        mapper.insert(row);
    }

    public SysApiKeyDO findById(String id) {
        return mapper.selectById(id);
    }

    public SysApiKeyDO findByHash(String keyHash) {
        return mapper.selectOne(Wrappers.<SysApiKeyDO>lambdaQuery()
                .eq(SysApiKeyDO::getKeyHash, keyHash));
    }

    public List<SysApiKeyDO> listAll() {
        return mapper.selectList(Wrappers.<SysApiKeyDO>lambdaQuery()
                .orderByDesc(SysApiKeyDO::getCreateAt));
    }

    public List<SysApiKeyDO> listByWorkspace(String workspaceId) {
        return mapper.selectList(Wrappers.<SysApiKeyDO>lambdaQuery()
                .eq(SysApiKeyDO::getWorkspaceId, workspaceId)
                .orderByDesc(SysApiKeyDO::getCreateAt));
    }

    public void softDelete(String id) {
        mapper.deleteById(id);
    }

    public void clearWorkspaceBinding(String id) {
        mapper.update(null, Wrappers.<SysApiKeyDO>lambdaUpdate()
                .eq(SysApiKeyDO::getId, id)
                .set(SysApiKeyDO::getWorkspaceId, null));
    }

    public void touchLastUsed(String id, Instant at) {
        mapper.update(null, Wrappers.<SysApiKeyDO>lambdaUpdate()
                .eq(SysApiKeyDO::getId, id)
                .set(SysApiKeyDO::getLastUsedAt, at)
                .set(SysApiKeyDO::getUpdateAt, at));
    }
}
