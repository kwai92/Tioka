package com.tioka.orchestration.application.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.decide.OrchDecideService;
import com.tioka.orchestration.application.decide.StrengthResolver;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import com.tioka.orchestration.infrastructure.persistence.OrchFactDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * OrchApi.ingest 实现（ORC-03）。
 */
@Service
public class OrchIngestService {

    private final OrchEngineRepository repo;
    private final OrchDecideService decideService;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final int maxHops;

    public OrchIngestService(OrchEngineRepository repo,
                             OrchDecideService decideService,
                             PrincipalHolder principals,
                             ObjectMapper mapper,
                             @Value("${tioka.orch.max-hops:8}") int maxHops) {
        this.repo = repo;
        this.decideService = decideService;
        this.principals = principals;
        this.mapper = mapper;
        this.maxHops = maxHops;
    }

    @Transactional
    public OrchApi.EventAck ingest(OrchApi.EventEnvelope event) {
        validate(event);
        if (event.hops() > maxHops) {
            String eventId = storeFiltered(event, OrchApi.Strength.INFERRED, "hops_exceeded");
            return OrchApi.EventAck.filtered(eventId);
        }

        var existing = repo.findEventIdByIdempotencyKey(event.idempotencyKey());
        if (existing.isPresent()) {
            return OrchApi.EventAck.duplicate(existing.get());
        }

        OrchApi.Strength strength = StrengthResolver.resolve(event.attrs(), false);
        String eventId;
        try {
            eventId = insertStored(event, strength);
        } catch (DuplicateKeyException e) {
            return OrchApi.EventAck.duplicate(
                    repo.findEventIdByIdempotencyKey(event.idempotencyKey()).orElse(""));
        }

        List<OrchRuleDO> rules = repo.findActiveRulesForEventType(event.type());
        if (rules.isEmpty()) {
            repo.updateEventDisposition(eventId, OrchApi.Disposition.FILTERED.name());
            return OrchApi.EventAck.filtered(eventId);
        }

        String workspaceId = resolveWorkspaceId();
        for (OrchRuleDO rule : rules) {
            decideService.decide(eventId, event, rule, strength, workspaceId);
        }
        return OrchApi.EventAck.stored(eventId);
    }

    public OrchApi.RunView getRun(String runId) {
        OrchRunDO r = repo.findRun(runId);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在");
        }
        return toRunView(r);
    }

    @Transactional
    public void cancelRun(String runId, String reason) {
        OrchRunDO run = repo.lockRun(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在");
        }
        OrchApi.RunStatus status = OrchApi.RunStatus.valueOf(run.getStatus());
        if (OrchApi.RunStatus.SUCCEEDED == status
                || OrchApi.RunStatus.FAILED == status
                || OrchApi.RunStatus.CANCELLED == status) {
            return;
        }
        run.setStatus(OrchApi.RunStatus.CANCELLED.name());
        run.setErrorCode("CANCELLED");
        run.setErrorMessage(reason);
        run.setEndedAt(LocalDateTime.now());
        run.setUpdateAt(LocalDateTime.now());
        repo.updateRun(run);
        repo.appendFact(runId, "cancelled", json(Map.of("reason", reason == null ? "" : reason)));
        clearActiveIfNeeded(run);
    }

    public List<OrchApi.FactView> facts(String runId, long afterSeq) {
        return repo.listFacts(runId, afterSeq).stream()
                .map(this::toFactView)
                .toList();
    }

    private void validate(OrchApi.EventEnvelope e) {
        if (e == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "event 必填");
        }
        require(e.source(), "source");
        require(e.type(), "type");
        require(e.idempotencyKey(), "idempotencyKey");
        if (e.attrs() != null) {
            e.attrs().forEach((k, v) -> {
                if (k != null && k.length() > 128) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "attrs 键过长");
                }
                if (v != null && v.length() > 2048) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "attrs 值过长");
                }
            });
        }
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, name + " 必填");
        }
    }

    private String insertStored(OrchApi.EventEnvelope event, OrchApi.Strength strength) {
        String id = Ids.newId("oe");
        OrchEventDO row = new OrchEventDO();
        row.setId(id);
        row.setWorkspaceId(resolveWorkspaceId());
        row.setSource(event.source());
        row.setType(event.type());
        row.setIdempotencyKey(event.idempotencyKey());
        row.setSubjectKey(event.subjectKey());
        row.setAttrsJson(json(event.attrs()));
        row.setBodyJson(json(event.body()));
        row.setHops(event.hops());
        row.setDisposition(OrchApi.Disposition.STORED.name());
        row.setStrength(strength.name());
        row.setReceivedAt(LocalDateTime.now());
        repo.insertEvent(row);
        return id;
    }

    private String storeFiltered(OrchApi.EventEnvelope event, OrchApi.Strength strength, String reason) {
        String id = Ids.newId("oe");
        OrchEventDO row = new OrchEventDO();
        row.setId(id);
        row.setWorkspaceId(resolveWorkspaceId());
        row.setSource(event.source());
        row.setType(event.type());
        row.setIdempotencyKey(event.idempotencyKey() + ":filtered:" + reason);
        row.setSubjectKey(event.subjectKey());
        row.setAttrsJson(json(event.attrs()));
        row.setBodyJson(json(event.body()));
        row.setHops(event.hops());
        row.setDisposition(OrchApi.Disposition.FILTERED.name());
        row.setStrength(strength.name());
        row.setReceivedAt(LocalDateTime.now());
        try {
            repo.insertEvent(row);
        } catch (DuplicateKeyException ignored) {
            // ignore
        }
        return id;
    }

    private void clearActiveIfNeeded(OrchRunDO run) {
        var wi = repo.findWorkItem(run.getWorkItemId());
        if (wi != null && run.getId().equals(wi.getActiveRunId())) {
            wi.setActiveRunId(null);
            wi.setUpdateAt(LocalDateTime.now());
            repo.updateWorkItem(wi);
        }
    }

    private String resolveWorkspaceId() {
        return principals.find().map(Principal::workspaceId).orElse(null);
    }

    private OrchApi.RunView toRunView(OrchRunDO r) {
        OrchApi.RunStatus status;
        try {
            status = OrchApi.RunStatus.valueOf(r.getStatus());
        } catch (Exception e) {
            status = OrchApi.RunStatus.PENDING;
        }
        Instant created = r.getCreateAt() == null ? Instant.EPOCH
                : r.getCreateAt().toInstant(ZoneOffset.UTC);
        Instant ended = r.getEndedAt() == null ? null
                : r.getEndedAt().toInstant(ZoneOffset.UTC);
        Map<String, Object> receipt = Map.of();
        if (r.getReceiptJson() != null && !r.getReceiptJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(r.getReceiptJson(), Map.class);
                receipt = parsed;
            } catch (Exception ignored) {
                receipt = Map.of();
            }
        }
        var rule = repo.findVersion(r.getRuleVersionId());
        return new OrchApi.RunView(
                r.getId(),
                r.getWorkItemId(),
                rule == null ? null : rule.getRuleId(),
                r.getRuleVersionId(),
                status,
                r.getAttempt() == null ? 1 : r.getAttempt(),
                r.getThreadId(),
                r.getCurrentTurnId(),
                receipt,
                r.getErrorCode(),
                r.getErrorMessage(),
                created,
                ended);
    }

    private OrchApi.FactView toFactView(OrchFactDO f) {
        Map<String, Object> detail = Map.of();
        if (f.getDetailJson() != null && !f.getDetailJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(f.getDetailJson(), Map.class);
                detail = parsed;
            } catch (Exception ignored) {
                detail = Map.of();
            }
        }
        Instant at = f.getAt() == null ? Instant.EPOCH : f.getAt().toInstant(ZoneOffset.UTC);
        return new OrchApi.FactView(f.getSeq(), f.getKind(), detail, at);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("json", e);
        }
    }
}
