package com.tioka.orchestration.application;

import com.tioka.framework.error.NotImplemented;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.OrchRuleRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * engine.OrchApi 桩：ORC-05 删除旧表实现后，接入/派工由 ORC-03 重建。
 */
@Service
public class OrchEngineService implements OrchApi {
    private final OrchRuleRepository repo;

    public OrchEngineService(OrchRuleRepository repo) {
        this.repo = repo;
    }

    @Override
    public EventAck ingest(EventEnvelope event) {
        Span span = GlobalOpenTelemetry.getTracer("tioka").spanBuilder("orch.ingest").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (event != null && event.type() != null) {
                span.setAttribute("tioka.event_type", event.type());
            }
            throw NotImplemented.of("OrchApi.ingest", "ORC-03");
        } catch (RuntimeException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public RunView getRun(String runId) {
        OrchRunDO r = repo.findRun(runId);
        if (r == null) {
            throw NotImplemented.of("OrchApi.getRun", "ORC-03");
        }
        RunStatus status;
        try {
            status = RunStatus.valueOf(r.getStatus());
        } catch (Exception e) {
            status = RunStatus.PENDING;
        }
        Instant created = r.getCreateAt() == null ? Instant.EPOCH
                : r.getCreateAt().toInstant(ZoneOffset.UTC);
        Instant ended = r.getEndedAt() == null ? null
                : r.getEndedAt().toInstant(ZoneOffset.UTC);
        return new RunView(
                r.getId(),
                r.getWorkItemId(),
                null,
                r.getRuleVersionId(),
                status,
                r.getAttempt() == null ? 1 : r.getAttempt(),
                r.getThreadId(),
                r.getCurrentTurnId(),
                Map.of(),
                r.getErrorCode(),
                r.getErrorMessage(),
                created,
                ended);
    }

    @Override
    public void cancelRun(String runId, String reason) {
        throw NotImplemented.of("OrchApi.cancelRun", "ORC-03");
    }

    @Override
    public List<FactView> facts(String runId, long afterSeq) {
        throw NotImplemented.of("OrchApi.facts", "ORC-03");
    }
}
