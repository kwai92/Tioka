package com.tioka.connector.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.connector.infrastructure.mapper.ImOutboxMapper;
import com.tioka.connector.infrastructure.persistence.ImOutboxDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ImOutboxRepository {
    private final ImOutboxMapper mapper;
    public ImOutboxRepository(ImOutboxMapper mapper) { this.mapper = mapper; }
    public void insert(String channel, String payloadJson) {
        ImOutboxDO row = new ImOutboxDO();
        row.setChannel(channel);
        row.setPayloadJson(payloadJson);
        mapper.insert(row);
    }
    public List<ImOutboxDO> findByChannel(String channel) {
        return mapper.selectList(Wrappers.<ImOutboxDO>lambdaQuery()
                .eq(ImOutboxDO::getChannel, channel).orderByAsc(ImOutboxDO::getId));
    }
}
