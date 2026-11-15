package com.tioka.runtime.application.metrics;

import com.tioka.runtime.api.metrics.StructuredOutputStatsApi;
import com.tioka.runtime.infrastructure.mapper.RuntimeItemStatsMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemStatsRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * SCH-10（ADR-066 §10.1）：结构化输出指标聚合的默认实现。
 *
 * <p>不解析 {@code content_json}：直接读 {@code runtime_item} 上由 OutputStage 写入的投影列
 * （V232 结局列 + V234 维度列）。时间窗口与 {@code ItemRepository#listByRange} 同口径（UTC）。
 */
@Service
public class StructuredOutputStatsService implements StructuredOutputStatsApi {

    private final RuntimeItemStatsMapper mapper;

    public StructuredOutputStatsService(RuntimeItemStatsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Bucket> aggregate(Instant from, Instant to) {
        List<RuntimeItemStatsRow> rows = mapper.aggregateDataItems(utc(from), utc(to));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(StructuredOutputStatsService::toBucket).toList();
    }

    private static Bucket toBucket(RuntimeItemStatsRow r) {
        return new Bucket(
                r.getSchemaRef(), r.getModelRef(), r.getOutputMode(),
                value(r.getTotal()), value(r.getFirstPass()), value(r.getFinalPass()),
                value(r.getFallback()), value(r.getHumanPending()), value(r.getFailed()),
                r.getAvgRepairRounds() == null ? 0d : r.getAvgRepairRounds());
    }

    private static long value(Long n) {
        return n == null ? 0L : n;
    }

    private static LocalDateTime utc(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
