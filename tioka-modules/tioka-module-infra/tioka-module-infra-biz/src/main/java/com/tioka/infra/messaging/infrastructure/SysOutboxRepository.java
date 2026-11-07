package com.tioka.infra.messaging.infrastructure;

import com.tioka.infra.messaging.infrastructure.mapper.SysOutboxMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class SysOutboxRepository {

    private final SysOutboxMapper mapper;

    public SysOutboxRepository(SysOutboxMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(SysOutboxDO row) {
        mapper.insert(row);
    }

    public void notifyChannel() {
        mapper.notifyOutbox();
    }

    public List<SysOutboxDO> findUnsent(int limit) {
        return mapper.selectUnsent(limit);
    }

    public boolean markSent(long id, Instant sentAt) {
        return mapper.markSent(id, sentAt) > 0;
    }

    public void markAttemptFailed(long id, String lastError) {
        String truncated = lastError == null ? null
                : (lastError.length() > 2000 ? lastError.substring(0, 2000) : lastError);
        mapper.markAttemptFailed(id, truncated);
    }

    public int deleteSentBefore(Instant before) {
        return mapper.deleteSentBefore(before);
    }
}
