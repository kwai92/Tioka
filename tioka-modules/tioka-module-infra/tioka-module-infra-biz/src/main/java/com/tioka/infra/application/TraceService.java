package com.tioka.infra.application;

import com.tioka.infra.infrastructure.TraceSpanRepository;
import com.tioka.infra.infrastructure.persistence.TraceSpanDO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * GOV-08：停写 {@code trace_span}；查询保留至 M4 删表。链路改由 OpenTelemetry / Jaeger。
 */
@Service
public class TraceService {
    private final TraceSpanRepository spans;

    public TraceService(TraceSpanRepository spans) {
        this.spans = spans;
    }

    /** 停写：自建表不再落库（Observability 改链 Jaeger）。 */
    public void record(String kind, String detail) {
        // no-op
    }

    public List<Span> query(String traceId) {
        return spans.findByTraceId(traceId).stream()
                .map(r -> new Span(r.getAtTs(), r.getKind(), r.getDetail()))
                .toList();
    }

    public record Span(String at, String kind, String detail) {}
}
