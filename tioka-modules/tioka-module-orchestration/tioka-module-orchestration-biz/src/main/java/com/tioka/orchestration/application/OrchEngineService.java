package com.tioka.orchestration.application;

import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.ingest.OrchIngestService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OrchApi 门面：委托 ORC-03 ingest 实现。
 */
@Service
public class OrchEngineService implements OrchApi {
    private final OrchIngestService ingest;

    public OrchEngineService(OrchIngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public EventAck ingest(EventEnvelope event) {
        Span span = GlobalOpenTelemetry.getTracer("tioka").spanBuilder("orch.ingest").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (event != null && event.type() != null) {
                span.setAttribute("tioka.event_type", event.type());
            }
            return ingest.ingest(event);
        } catch (RuntimeException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public RunView getRun(String runId) {
        return ingest.getRun(runId);
    }

    @Override
    public void cancelRun(String runId, String reason) {
        ingest.cancelRun(runId, reason);
    }

    @Override
    public List<FactView> facts(String runId, long afterSeq) {
        return ingest.facts(runId, afterSeq);
    }
}
