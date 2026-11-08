package com.tioka.connector.infrastructure;

import com.tioka.connector.infrastructure.mapper.ImInboundDedupeMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ImInboundDedupeRepository {

    private final ImInboundDedupeMapper mapper;

    public ImInboundDedupeRepository(ImInboundDedupeMapper mapper) {
        this.mapper = mapper;
    }

    /** @return true 若本次新写入（首次见到该 event） */
    public boolean tryInsert(String bindingId, String eventId) {
        return mapper.insertIgnore(bindingId, eventId) > 0;
    }
}
