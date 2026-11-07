package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.AuditEventMapper;
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

    public void insert(String id, Instant ts, String workspaceId, String actorType, String actorId,
                       String action, String resourceType, String resourceId, String outcome,
                       String reason, String traceId, String requestId, String clientIp, String detailsJson) {
        mapper.insert(id, ts, workspaceId, actorType, actorId, action, resourceType, resourceId,
                outcome, reason, traceId, requestId, clientIp, detailsJson);
    }

    public List<Map<String, Object>> query(String workspaceId, String actorId, String action,
                                           String resourceType, String resourceId, String outcome,
                                           Instant from, Instant to, int limit, int offset) {
        return mapper.query(workspaceId, actorId, action, resourceType, resourceId, outcome,
                from, to, limit, offset);
    }

    /** 兼容旧调用（FND-04 IT）。 */
    public List<Map<String, Object>> query(String workspaceId, String actorId, String action,
                                           String outcome, int limit) {
        return query(workspaceId, actorId, action, null, null, outcome, null, null, limit, 0);
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
