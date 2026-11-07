package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.OrchRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * engine.OrchApi 过渡实现：ingest/facts/getRun 桥接现有 OrchService / 表结构。
 * ORC-03 + V132 落地后替换为本域完整引擎。
 */
@Service
public class OrchEngineService implements OrchApi {
    private final OrchService legacy;
    private final OrchRepository repo;

    public OrchEngineService(OrchService legacy, OrchRepository repo) {
        this.legacy = legacy;
        this.repo = repo;
    }

    @Override
    public EventAck ingest(EventEnvelope event) {
        String runId = legacy.ingestEvent(
                event.type(),
                event.idempotencyKey(),
                event.body() == null ? Map.of() : event.body());
        if (runId == null || runId.isBlank()) {
            return new EventAck(Ids.newId("evt"), Disposition.FILTERED);
        }
        // 过渡：eventId 回传 legacy runId，便于人审面 openAttention 挂载
        return new EventAck(runId, Disposition.STORED);
    }

    @Override
    public RunView getRun(String runId) {
        OrchRunDO r = repo.findRun(runId);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在");
        }
        return toView(r);
    }

    @Override
    public void cancelRun(String runId, String reason) {
        OrchRunDO r = repo.findRun(runId);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在");
        }
        r.setStatus("CANCELLED");
        repo.updateRun(r);
    }

    @Override
    public List<FactView> facts(String runId, long afterSeq) {
        return legacy.why(runId).stream()
                .skip(Math.max(0, afterSeq))
                .map(w -> new FactView(
                        0L,
                        w.kind(),
                        Map.of("detail", w.detail() == null ? "" : w.detail()),
                        parseInstant(w.at())))
                .toList();
    }

    private static RunView toView(OrchRunDO r) {
        RunStatus status = mapStatus(r.getStatus());
        return new RunView(
                r.getId(),
                null,
                null,
                null,
                status,
                0,
                null,
                null,
                Map.of(),
                null,
                null,
                Instant.now(),
                status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED
                        ? Instant.now() : null);
    }

    private static RunStatus mapStatus(String raw) {
        if (raw == null) {
            return RunStatus.PENDING;
        }
        return switch (raw.toUpperCase()) {
            case "READY", "LEASED" -> RunStatus.PENDING;
            case "RUNNING" -> RunStatus.RUNNING;
            case "SUSPENDED" -> RunStatus.WAITING_ATTENTION;
            case "SUCCEEDED", "COMPLETED" -> RunStatus.SUCCEEDED;
            case "FAILED" -> RunStatus.FAILED;
            case "CANCELLED" -> RunStatus.CANCELLED;
            default -> RunStatus.PENDING;
        };
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
