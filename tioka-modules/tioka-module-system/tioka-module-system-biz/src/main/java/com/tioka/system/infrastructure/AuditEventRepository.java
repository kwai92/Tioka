package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.AuditEventMapper;
import com.tioka.system.infrastructure.persistence.AuditEventQuery;
import com.tioka.system.infrastructure.persistence.AuditEventWriteRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AuditEventRepository {
    private final AuditEventMapper mapper;

    public AuditEventRepository(AuditEventMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(AuditEventWriteRow row) {
        mapper.insert(row);
    }

    public List<Map<String, Object>> query(AuditEventQuery q) {
        return mapper.query(q);
    }

    /** 兼容旧调用（FND-04 IT）。 */
    public List<Map<String, Object>> query(String workspaceId, String actorId, String action,
                                           String outcome, int limit) {
        return query(new AuditEventQuery(workspaceId, actorId, action, null, null, outcome,
                null, null, limit, 0));
    }

    public List<Map<String, Object>> listUnforwarded(int limit) {
        return mapper.listUnforwarded(limit);
    }

    public void markForwarded(List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                mapper.markForwardedOne(id);
            }
        }
    }

    public int deleteExpiredForwarded(Instant before) {
        return mapper.deleteExpiredForwarded(before);
    }
}
