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
                       String reason, String traceId, String clientIp, String detailsJson) {
        mapper.insert(id, ts, workspaceId, actorType, actorId, action, resourceType, resourceId,
                outcome, reason, traceId, clientIp, detailsJson);
    }

    public List<Map<String, Object>> query(String workspaceId, String actorId, String action,
                                           String outcome, int limit) {
        return mapper.query(workspaceId, actorId, action, outcome, limit);
    }
}
