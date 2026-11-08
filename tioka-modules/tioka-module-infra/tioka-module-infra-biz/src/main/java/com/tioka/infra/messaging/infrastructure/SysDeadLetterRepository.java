package com.tioka.infra.messaging.infrastructure;

import com.tioka.infra.messaging.infrastructure.mapper.SysDeadLetterMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import org.springframework.stereotype.Repository;

@Repository
public class SysDeadLetterRepository {

    private final SysDeadLetterMapper mapper;

    public SysDeadLetterRepository(SysDeadLetterMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(SysDeadLetterDO row) {
        mapper.insert(row);
    }
}
