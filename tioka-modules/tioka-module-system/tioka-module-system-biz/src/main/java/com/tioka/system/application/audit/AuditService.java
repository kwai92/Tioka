package com.tioka.system.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.infrastructure.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FND-04 最小审计写入（DENIED）；完整管道见 GOV-02。
 */
@Service
public class AuditService implements AuditApi {
    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(AuditEvent event) {
        write(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(AuditEvent event) {
        write(event);
    }

    @Override
    public List<AuditEvent> query(AuditQuery query) {
        int limit = query.limit() <= 0 ? 50 : Math.min(query.limit(), 200);
        String outcome = query.outcome() == null ? null : query.outcome().name();
        List<Map<String, Object>> rows = repository.query(
                query.workspaceId(), query.actorId(), query.action(), outcome, limit);
        List<AuditEvent> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new AuditEvent(
                    str(r.get("id")),
                    toInstant(r.get("ts")),
                    null,
                    str(r.get("workspaceId")),
                    str(r.get("actorType")),
                    str(r.get("actorId")),
                    str(r.get("action")),
                    str(r.get("resourceType")),
                    str(r.get("resourceId")),
                    AuditOutcome.valueOf(str(r.get("outcome"))),
                    str(r.get("reason")),
                    str(r.get("traceId")),
                    str(r.get("clientIp")),
                    Map.of()));
        }
        return out;
    }

    private void write(AuditEvent event) {
        AuditEvent e = event;
        if (e.id() == null || e.id().isBlank()) {
            e = new AuditEvent(Ids.newId("aud"), e.ts() == null ? Instant.now() : e.ts(),
                    e.tenantId(), e.workspaceId(), e.actorType(), e.actorId(), e.action(),
                    e.resourceType(), e.resourceId(), e.outcome(), e.reason(), e.traceId(),
                    e.clientIp(), e.details());
        }
        String detailsJson = null;
        if (e.details() != null && !e.details().isEmpty()) {
            try {
                detailsJson = objectMapper.writeValueAsString(e.details());
            } catch (JsonProcessingException ignored) {
                detailsJson = "{}";
            }
        }
        repository.insert(e.id(), e.ts() == null ? Instant.now() : e.ts(), e.workspaceId(),
                e.actorType(), e.actorId(), e.action(), e.resourceType(), e.resourceId(),
                e.outcome().name(), e.reason(), e.traceId(), e.clientIp(), detailsJson);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Instant toInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(String.valueOf(o));
    }
}
