package com.tioka.system.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.id.Ids;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.infrastructure.AuditEventRepository;
import com.tioka.system.infrastructure.persistence.AuditEventQuery;
import com.tioka.system.infrastructure.persistence.AuditEventWriteRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计管道写入与查询（GOV-02）。
 *
 * <p>SUCCESS 与业务同事务（有事务则加入；无事务则 REQUIRES_NEW）；
 * DENIED/ERROR 经 {@link #recordIndependent} 独立事务落库。
 */
@Service
public class AuditService implements AuditApi {
    private static final int DEFAULT_QUERY_LIMIT = 50;
    private static final int MAX_QUERY_LIMIT = 200;
    private static final int MAX_EXPORT_ROWS = 100_000;

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditService self;
    /** P2-L6：审计 service 列取值（与 OTel service-name 同源）。 */
    private final String serviceName;

    public AuditService(AuditEventRepository repository,
                        ObjectMapper objectMapper,
                        @Lazy AuditService self,
                        @Value("${tioka.observability.service-name:tioka}") String serviceName) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.self = self;
        this.serviceName = serviceName == null || serviceName.isBlank() ? "tioka" : serviceName.trim();
    }

    @Override
    public void record(AuditEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            self.writeJoining(event);
        } else {
            self.writeIndependent(event);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(AuditEvent event) {
        write(event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void writeJoining(AuditEvent event) {
        write(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeIndependent(AuditEvent event) {
        write(event);
    }

    @Override
    public List<AuditEvent> query(AuditQuery query) {
        int limit = query.limit() <= 0 ? DEFAULT_QUERY_LIMIT : Math.min(query.limit(), MAX_QUERY_LIMIT);
        int offset = 0;
        if (query.cursor() != null && !query.cursor().isBlank()) {
            try {
                offset = Math.max(0, Integer.parseInt(query.cursor()));
            } catch (NumberFormatException ignored) {
                offset = 0;
            }
        }
        String outcome = query.outcome() == null ? null : query.outcome().name();
        List<Map<String, Object>> rows = repository.query(new AuditEventQuery(
                query.workspaceId(), query.actorId(), query.action(),
                query.resourceType(), query.resourceId(), outcome,
                query.from(), query.to(), limit, offset));
        List<AuditEvent> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(toEvent(r));
        }
        return out;
    }

    /** 导出用：最多 {@link #MAX_EXPORT_ROWS} 行。 */
    public List<AuditEvent> queryForExport(AuditQuery query, int maxRows) {
        int limit = Math.min(Math.max(maxRows, 1), MAX_EXPORT_ROWS);
        String outcome = query.outcome() == null ? null : query.outcome().name();
        List<Map<String, Object>> rows = repository.query(new AuditEventQuery(
                query.workspaceId(), query.actorId(), query.action(),
                query.resourceType(), query.resourceId(), outcome,
                query.from(), query.to(), limit, 0));
        List<AuditEvent> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(toEvent(r));
        }
        return out;
    }

    private void write(AuditEvent event) {
        AuditEvent e = enrich(event);
        String detailsJson = null;
        if (e.details() != null && !e.details().isEmpty()) {
            try {
                detailsJson = objectMapper.writeValueAsString(e.details());
            } catch (JsonProcessingException ignored) {
                detailsJson = "{}";
            }
        }
        String subject = RequestContextHolder.getCorrelation().subject();
        repository.insert(new AuditEventWriteRow(
                e.id(), e.ts(), e.workspaceId(),
                e.actorType(), e.actorId(), e.action(), e.resourceType(), e.resourceId(),
                e.outcome().name(), e.reason(), e.traceId(), e.spanId(), e.service(), e.requestId(),
                subject, e.clientIp(), detailsJson));
    }

    /** OBS-01：补齐 id/ts/traceId/requestId；P2-L6：补齐 spanId（MDC）与 service。 */
    private AuditEvent enrich(AuditEvent e) {
        String id = blank(e.id()) ? Ids.newId("aud") : e.id();
        Instant ts = e.ts() == null ? Instant.now() : e.ts();
        String trace = blank(e.traceId()) ? AuditRequestContext.currentTraceId() : e.traceId();
        String requestId = blank(e.requestId()) ? AuditRequestContext.currentRequestId() : e.requestId();
        String spanId = blank(e.spanId()) ? AuditRequestContext.currentSpanId() : e.spanId();
        String service = blank(e.service()) ? serviceName : e.service();
        return AuditEvent.builder()
                .id(id)
                .ts(ts)
                .workspaceId(e.workspaceId())
                .actorType(e.actorType())
                .actorId(e.actorId())
                .action(e.action())
                .resourceType(e.resourceType())
                .resourceId(e.resourceId())
                .outcome(e.outcome())
                .reason(e.reason())
                .traceId(trace)
                .spanId(spanId)
                .service(service)
                .requestId(requestId)
                .clientIp(e.clientIp())
                .details(e.details())
                .build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private AuditEvent toEvent(Map<String, Object> r) {
        Map<String, Object> details = Map.of();
        String detailsJson = str(r.get("detailsJson"));
        if (detailsJson != null && !detailsJson.isBlank()) {
            try {
                details = objectMapper.readValue(detailsJson, new TypeReference<>() {});
            } catch (JsonProcessingException ignored) {
                details = Map.of();
            }
        }
        return AuditEvent.builder()
                .id(str(r.get("id")))
                .ts(toInstant(r.get("ts")))
                .workspaceId(str(r.get("workspaceId")))
                .actorType(str(r.get("actorType")))
                .actorId(str(r.get("actorId")))
                .action(str(r.get("action")))
                .resourceType(str(r.get("resourceType")))
                .resourceId(str(r.get("resourceId")))
                .outcome(AuditOutcome.valueOf(str(r.get("outcome"))))
                .reason(str(r.get("reason")))
                .traceId(str(r.get("traceId")))
                .spanId(str(r.get("spanId")))
                .service(str(r.get("service")))
                .requestId(str(r.get("requestId")))
                .clientIp(str(r.get("clientIp")))
                .details(details)
                .build();
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
