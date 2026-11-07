package com.tioka.orchestration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.OrchRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * V132 薄 stub：ingest 只落 {@code orch_event}（FILTERED，不建 Run）；
 * getRun/facts/cancel 读新表。完整决定/派工见 ORC-05/03。
 */
@Service
public class OrchEngineService implements OrchApi {
    private final OrchRepository repo;
    private final ObjectMapper mapper;
    private final PrincipalHolder principals;

    public OrchEngineService(OrchRepository repo, ObjectMapper mapper, PrincipalHolder principals) {
        this.repo = repo;
        this.mapper = mapper;
        this.principals = principals;
    }

    @Override
    public EventAck ingest(EventEnvelope event) {
        String idem = event.idempotencyKey();
        if (idem != null && !idem.isBlank()) {
            OrchEventDO existing = repo.findEventByIdem(idem);
            if (existing != null) {
                return new EventAck(existing.getId(), Disposition.DUPLICATE);
            }
        }
        String eventId = Ids.newId("evt");
        OrchEventDO row = new OrchEventDO();
        row.setId(eventId);
        row.setWorkspaceId(principals.find().map(p -> p.workspaceId()).orElse(null));
        row.setSource(event.source() == null ? "unknown" : event.source());
        row.setType(event.type());
        row.setIdempotencyKey(idem == null || idem.isBlank() ? eventId : idem);
        row.setSubjectKey(event.subjectKey());
        row.setAttrsJson(writeJson(event.attrs()));
        row.setBodyJson(writeJson(event.body()));
        row.setHops(event.hops());
        // 本波无规则引擎：一律 FILTERED，不建 work_item/run
        row.setDisposition(Disposition.FILTERED.name());
        row.setStrength(Strength.EXPLICIT.name());
        row.setReceivedAt(Instant.now());
        try {
            repo.insertEvent(row);
        } catch (DuplicateKeyException e) {
            OrchEventDO again = repo.findEventByIdem(row.getIdempotencyKey());
            return new EventAck(again == null ? eventId : again.getId(), Disposition.DUPLICATE);
        }
        return new EventAck(eventId, Disposition.FILTERED);
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
        r.setStatus(RunStatus.CANCELLED.name());
        r.setErrorMessage(reason);
        r.setEndedAt(Instant.now());
        r.setUpdateAt(Instant.now());
        repo.updateRun(r);
    }

    @Override
    public List<FactView> facts(String runId, long afterSeq) {
        // orch_fact 表已建；本波不读写，返回空（ORC-03 补）
        if (repo.findRun(runId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在");
        }
        return List.of();
    }

    private static RunView toView(OrchRunDO r) {
        RunStatus status;
        try {
            status = RunStatus.valueOf(r.getStatus());
        } catch (Exception e) {
            status = RunStatus.PENDING;
        }
        return new RunView(
                r.getId(),
                r.getWorkItemId(),
                null,
                r.getRuleVersionId(),
                status,
                r.getAttempt() == null ? 0 : r.getAttempt(),
                r.getThreadId(),
                r.getCurrentTurnId(),
                Map.of(),
                r.getErrorCode(),
                r.getErrorMessage(),
                r.getCreateAt() == null ? Instant.EPOCH : r.getCreateAt(),
                r.getEndedAt());
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
