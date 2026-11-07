package com.tioka.infra.messaging.infrastructure;

import com.tioka.infra.messaging.infrastructure.mapper.SysConsumedMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SysConsumedRepository {

    private final SysConsumedMapper mapper;

    public SysConsumedRepository(SysConsumedMapper mapper) {
        this.mapper = mapper;
    }

    /** @return true 若本行新插入（首次消费）；false 表示已消费过。 */
    public boolean tryMarkConsumed(String consumer, String eventId) {
        return mapper.insertIgnore(consumer, eventId) > 0;
    }
}
