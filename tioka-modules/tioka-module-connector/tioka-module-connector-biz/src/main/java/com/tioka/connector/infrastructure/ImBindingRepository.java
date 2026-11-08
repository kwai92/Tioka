package com.tioka.connector.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.connector.infrastructure.mapper.ImBindingMapper;
import com.tioka.connector.infrastructure.persistence.ImBindingDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ImBindingRepository {

    private final ImBindingMapper mapper;

    public ImBindingRepository(ImBindingMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(ImBindingDO row) {
        mapper.insert(row);
    }

    public void updateById(ImBindingDO row) {
        mapper.updateById(row);
    }

    public void softDeleteById(String id) {
        mapper.deleteById(id);
    }

    public Optional<ImBindingDO> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    public List<ImBindingDO> listByWorkspace(String workspaceId) {
        return mapper.selectList(Wrappers.<ImBindingDO>lambdaQuery()
                .eq(ImBindingDO::getWorkspaceId, workspaceId)
                .orderByDesc(ImBindingDO::getCreateAt));
    }

    public Optional<ImBindingDO> findByChannelAndAppId(String channel, String appId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ImBindingDO>lambdaQuery()
                .eq(ImBindingDO::getChannel, channel)
                .eq(ImBindingDO::getAppId, appId)));
    }
}
