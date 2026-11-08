package com.tioka.orchestration.application.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.context.RequestContext;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.orchestration.api.engine.AssignCommand;
import com.tioka.orchestration.api.engine.AssignResult;
import com.tioka.orchestration.api.engine.ConcurrencyMode;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.api.mq.OrchestrationMqTopics;
import com.tioka.orchestration.application.decide.OrchStateMachine;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchMandateDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACCEPT 后归单：工单 + Mandate + Run（ORC-03）。
 */
@Service
public class OrchAssignService {

    private final OrchEngineRepository repo;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    public OrchAssignService(OrchEngineRepository repo, EventPublisher events, ObjectMapper mapper) {
        this.repo = repo;
        this.events = events;
        this.mapper = mapper;
    }

    public AssignResult assign(AssignCommand cmd, String workspaceId, String followupWorkItemId) {
        LocalDateTime now = LocalDateTime.now();
        OrchWorkItemDO wi = lockOrCreate(cmd, workspaceId, followupWorkItemId, now);
        boolean createdWi = wi.getCreateAt() != null && wi.getCreateAt().equals(now)
                || (followupWorkItemId == null && wasJustCreated(wi, now));

        String mandateId;
        OrchMandateDO existingMandate = repo.findLatestMandate(wi.getId());
        if (existingMandate == null || "REVOKED".equals(existingMandate.getStatus())) {
            mandateId = insertMandate(cmd, wi.getId(), now);
            createdWi = existingMandate == null;
        } else {
            mandateId = existingMandate.getId();
        }

        boolean hasActive = wi.getActiveRunId() != null && !wi.getActiveRunId().isBlank();
        ConcurrencyMode mode = cmd.concurrency();

        if (OrchStateMachine.shouldAppendPendingOnly(mode, hasActive)) {
            appendPending(wi, cmd.eventId(), now);
            return new AssignResult(wi.getId(), mandateId, null, createdWi, false, false, mode);
        }

        boolean queued = OrchStateMachine.shouldQueueSerial(mode, hasActive);
        String runId = insertRun(cmd, wi, workspaceId, now);
        if (!queued) {
            wi.setActiveRunId(runId);
        }
        wi.setLastEventAt(now);
        wi.setUpdateAt(now);
        wi.setStatus("OPEN");
        repo.updateWorkItem(wi);

        publishRunCreated(runId, wi, cmd, workspaceId);
        repo.appendFact(runId, "run_created", json(Map.of(
                "eventId", cmd.eventId(),
                "queued", queued,
                "concurrency", mode.wire())));

        return new AssignResult(wi.getId(), mandateId, runId, createdWi, true, queued, mode);
    }

    private OrchWorkItemDO lockOrCreate(AssignCommand cmd, String workspaceId,
                                        String followupWorkItemId, LocalDateTime now) {
        if (followupWorkItemId != null) {
            OrchWorkItemDO existing = repo.findWorkItem(followupWorkItemId);
            if (existing != null) {
                OrchWorkItemDO locked = repo.lockWorkItem(existing.getRuleId(), existing.getSubjectKey());
                return locked != null ? locked : existing;
            }
        }
        OrchWorkItemDO locked = repo.lockWorkItem(cmd.ruleId(), cmd.subjectKey());
        if (locked != null) {
            return locked;
        }
        OrchWorkItemDO created = new OrchWorkItemDO();
        created.setId(Ids.newId("wi"));
        created.setWorkspaceId(workspaceId);
        created.setRuleId(cmd.ruleId());
        created.setSubjectKey(cmd.subjectKey());
        created.setStatus("OPEN");
        created.setCreateAt(now);
        created.setUpdateAt(now);
        created.setLastEventAt(now);
        try {
            repo.insertWorkItem(created);
            return created;
        } catch (DuplicateKeyException e) {
            OrchWorkItemDO again = repo.lockWorkItem(cmd.ruleId(), cmd.subjectKey());
            if (again == null) {
                throw e;
            }
            return again;
        }
    }

    private String insertMandate(AssignCommand cmd, String workItemId, LocalDateTime now) {
        String id = Ids.newId("md");
        OrchMandateDO row = new OrchMandateDO();
        row.setId(id);
        row.setWorkItemId(workItemId);
        row.setRuleVersionId(cmd.ruleVersionId());
        row.setServiceAccountId(cmd.serviceAccountId());
        row.setStrength(cmd.strength().name());
        row.setVia(cmd.via().name());
        row.setRequesterSource(cmd.requesterSource());
        row.setRequesterExternalId(cmd.requesterExternalId());
        row.setRequesterUserId(cmd.requesterUserId());
        row.setScopeJson(json(cmd.scopeJson()));
        row.setStatus("ACTIVE");
        row.setCreateAt(now);
        repo.insertMandate(row);
        return id;
    }

    private String insertRun(AssignCommand cmd, OrchWorkItemDO wi, String workspaceId, LocalDateTime now) {
        String runId = Ids.newId("or");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("eventId", cmd.eventId());
        input.put("attrs", cmd.eventAttrs());
        input.put("body", cmd.eventBody());

        Map<String, Object> runAs = new LinkedHashMap<>();
        runAs.put("userId", cmd.serviceAccountId());
        runAs.put("workspaceId", workspaceId);
        runAs.put("type", "SERVICE");

        // OBS-03：规则触发新 request_id；触发事件的 request_id 记为 parent
        RequestContext prev = RequestContextHolder.getCorrelation();
        String parent = blankToNull(prev.requestId());
        RequestContext correlation = new RequestContext(Ids.newId("req"), prev.subject(), parent);
        RequestContextHolder.setCorrelation(correlation);

        OrchRunDO run = new OrchRunDO();
        run.setId(runId);
        run.setWorkspaceId(workspaceId);
        run.setWorkItemId(wi.getId());
        run.setEventId(cmd.eventId());
        run.setRuleVersionId(cmd.ruleVersionId());
        run.setStatus(OrchApi.RunStatus.PENDING.name());
        run.setAttempt(1);
        run.setRewriteCount(0);
        run.setInputJson(json(input));
        run.setThreadId(wi.getThreadId());
        run.setRunAsJson(json(runAs));
        run.setRequestId(correlation.requestId());
        run.setSubject(correlation.subject());
        run.setCreateAt(now);
        run.setUpdateAt(now);
        repo.insertRun(run);
        return runId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void appendPending(OrchWorkItemDO wi, String eventId, LocalDateTime now) {
        List<String> pending = readPending(wi.getPendingEventsJson());
        pending.add(eventId);
        wi.setPendingEventsJson(json(pending));
        wi.setLastEventAt(now);
        wi.setUpdateAt(now);
        repo.updateWorkItem(wi);
    }

    private void publishRunCreated(String runId, OrchWorkItemDO wi, AssignCommand cmd, String workspaceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("workItemId", wi.getId());
        payload.put("ruleId", cmd.ruleId());
        payload.put("ruleVersionId", cmd.ruleVersionId());
        payload.put("workspaceId", workspaceId);
        events.publish(new DomainEvent(
                OrchestrationMqTopics.RUN_CREATED,
                OrchestrationMqTopics.TOPIC,
                runId,
                payload,
                0,
                null,
                "orch_run:" + runId));
    }

    private List<String> readPending(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(mapper.readValue(json, new TypeReference<>() {}));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("json", e);
        }
    }

    private static boolean wasJustCreated(OrchWorkItemDO wi, LocalDateTime now) {
        return wi.getCreateAt() != null && !wi.getCreateAt().isBefore(now.minusSeconds(1));
    }
}
