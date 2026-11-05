package com.tioka.infra.application;

import com.tioka.framework.trace.TraceContext;
import com.tioka.infra.infrastructure.TraceSpanRepository;
import com.tioka.infra.infrastructure.persistence.TraceSpanDO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TraceService {
    private final TraceSpanRepository spans;

    public TraceService(TraceSpanRepository spans) {
        this.spans = spans;
    }

    public void record(String kind, String detail) {
        TraceSpanDO row = new TraceSpanDO();
        row.setTraceId(TraceContext.getTraceId());
        row.setAtTs(Instant.now().toString());
        row.setKind(kind);
        row.setDetail(detail == null ? "" : detail);
        spans.insert(row);
    }

    public List<Span> query(String traceId) {
        return spans.findByTraceId(traceId).stream()
                .map(r -> new Span(r.getAtTs(), r.getKind(), r.getDetail()))
                .toList();
    }

    public record Span(String at, String kind, String detail) {}
}
