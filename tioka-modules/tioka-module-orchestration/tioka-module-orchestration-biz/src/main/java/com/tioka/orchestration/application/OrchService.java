package com.tioka.orchestration.application;

import com.tioka.framework.id.Ids;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchApi;
import com.tioka.orchestration.infrastructure.OrchRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchAttentionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchIdemDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWhyDO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class OrchService implements OrchApi {
    private final OrchRepository repo;
    private final OrchRuleService ruleService;
    private final ObjectMapper mapper;

    public OrchService(OrchRepository repo, OrchRuleService ruleService, ObjectMapper mapper) {
        this.mapper = mapper;
        this.repo = repo;
        this.ruleService = ruleService;
    }

    @Override
    public synchronized String ingestEvent(String eventType, String idempotencyKey, Map<String, Object> payload) {
        if (idempotencyKey != null) {
            String existing = repo.findRunIdByIdem(idempotencyKey);
            if (existing != null) return existing;
        }

        OrchRuleDO rule = ruleService.findPublished(eventType);
        Map<String, Object> action = rule == null
                ? Map.of("type", "create_run")
                : ruleService.parseAction(rule);
        String type = String.valueOf(action.getOrDefault("type", "create_run"));

        if ("ignore".equalsIgnoreCase(type)) {
            // 不建业务 Run；记一条哨兵 why（runId=ignored）便于排查
            appendWhy("ignored", "ignore", "eventType=" + eventType
                    + (rule == null ? "" : " rule=" + rule.getId()));
            if (idempotencyKey != null) {
                OrchIdemDO idem = new OrchIdemDO();
                idem.setIdempotencyKey(idempotencyKey);
                idem.setRunId("");
                repo.insertIdem(idem);
            }
            return "";
        }

        String runId = createReadyRun(eventType, idempotencyKey);
        if (rule != null) {
            appendWhy(runId, "rule", rule.getId() + " v" + rule.getVersion() + " action=" + type);
        }
        if ("open_attention".equalsIgnoreCase(type)) {
            String title = String.valueOf(action.getOrDefault("title", "Attention"));
            openAttention(runId, title, payload == null ? Map.of() : payload);
        }
        return runId;
    }

    private String createReadyRun(String eventType, String idempotencyKey) {
        String eventId = Ids.newId("evt");
        String runId = Ids.newId("run");
        OrchRunDO run = new OrchRunDO();
        run.setId(runId);
        run.setStatus("READY");
        run.setEventId(eventId);
        run.setWorkerId(null);
        run.setLeaseUntil(0L);
        repo.insertRun(run);
        appendWhy(runId, "event", eventType);
        if (idempotencyKey != null) {
            OrchIdemDO idem = new OrchIdemDO();
            idem.setIdempotencyKey(idempotencyKey);
            idem.setRunId(runId);
            repo.insertIdem(idem);
        }
        return runId;
    }

    @Override
    public synchronized RunView claim(String workerId) {
        List<OrchRunDO> ready = repo.listClaimable(System.currentTimeMillis());
        if (ready.isEmpty()) return null;
        OrchRunDO r = ready.get(0);
        long lease = System.currentTimeMillis() + 30_000;
        r.setStatus("LEASED");
        r.setWorkerId(workerId);
        r.setLeaseUntil(lease);
        repo.updateRun(r);
        appendWhy(r.getId(), "claim", workerId);
        return new RunView(r.getId(), "LEASED", r.getEventId(), workerId, lease);
    }

    @Override
    public void heartbeat(String runId, String workerId) {
        getRun(runId);
        OrchRunDO r = repo.findRun(runId);
        r.setWorkerId(workerId);
        r.setLeaseUntil(System.currentTimeMillis() + 30_000);
        repo.updateRun(r);
    }

    @Override
    public AttentionView openAttention(String runId, String title, Map<String, Object> card) {
        getRun(runId);
        String id = Ids.newId("att");
        AttentionView a = new AttentionView(id, runId, "OPEN", title, card, null);
        OrchAttentionDO row = new OrchAttentionDO();
        row.setId(id);
        row.setRunId(runId);
        row.setStatus("OPEN");
        row.setTitle(title);
        row.setCardJson(toJson(card));
        row.setAnswerJson(null);
        repo.insertAttention(row);
        OrchRunDO run = repo.findRun(runId);
        run.setStatus("SUSPENDED");
        repo.updateRun(run);
        appendWhy(runId, "attention", title);
        return a;
    }

    @Override
    public AttentionView answer(String attentionId, Map<String, Object> answer) {
        OrchAttentionDO found = repo.findAttention(attentionId);
        if (found == null) throw new BizException(ErrorCode.NOT_FOUND, "attention 不存在");
        AttentionView done = new AttentionView(found.getId(), found.getRunId(), "ANSWERED", found.getTitle(),
                fromMap(found.getCardJson()), answer);
        found.setStatus("ANSWERED");
        found.setAnswerJson(toJson(answer));
        repo.updateAttention(found);
        OrchRunDO run = repo.findRun(found.getRunId());
        run.setStatus("READY");
        run.setWorkerId(null);
        run.setLeaseUntil(0L);
        repo.updateRun(run);
        appendWhy(found.getRunId(), "answered", String.valueOf(answer));
        return done;
    }

    @Override
    public List<WhyStep> why(String runId) {
        return repo.listWhy(runId).stream()
                .map(w -> new WhyStep(w.getAtTs(), w.getKind(), w.getDetail()))
                .toList();
    }

    public List<AttentionView> openAttentions() {
        return repo.listOpenAttentions().stream()
                .map(r -> new AttentionView(r.getId(), r.getRunId(), r.getStatus(), r.getTitle(),
                        fromMap(r.getCardJson()), fromMap(r.getAnswerJson())))
                .toList();
    }

    private RunView getRun(String runId) {
        OrchRunDO r = repo.findRun(runId);
        if (r == null) throw new BizException(ErrorCode.NOT_FOUND, "run 不存在");
        return new RunView(r.getId(), r.getStatus(), r.getEventId(), r.getWorkerId(),
                r.getLeaseUntil() == null ? 0L : r.getLeaseUntil());
    }

    private void appendWhy(String runId, String kind, String detail) {
        int ord = repo.nextWhyOrd(runId);
        OrchWhyDO row = new OrchWhyDO();
        row.setId(Ids.newId("why"));
        row.setRunId(runId);
        row.setAtTs(Instant.now().toString());
        row.setKind(kind);
        row.setDetail(detail);
        row.setOrd(ord);
        repo.insertWhy(row);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> fromMap(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "JSON 解析失败");
        }
    }
}
