package com.tioka.governance.application.autonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyLevel;
import com.tioka.governance.infrastructure.autonomy.TrustAutonomyRepository;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyDO;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyEventDO;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyStatsDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自治等级管理实现（TRU-09）。等级存 {@code trust_autonomy}（初始 L1 由缺省逻辑兜底，
 * 不预写行）；每次变化写 {@code trust_autonomy_event}（ID 前缀 {@code aev}）+ 审计 +
 * {@code tioka.trust.autonomy_changed.v1}。{@code markIncident} 参数相对 ADR-034 扩为
 * （agentId, actionClass, actionId, note）——治理侧不可读 runtime 的 trust_action，
 * 由调用方带上归属（偏差登记 M3-C4）。
 */
public class DefaultAutonomyService implements AutonomyApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultAutonomyService.class);

    private final AutonomyLevelStore repository;
    private final AutonomyChangeNotifier notifier;
    private final SamplingPolicy sampler;
    private final ObjectProvider<AuditApi> auditApi;
    private final ObjectMapper mapper;

    public DefaultAutonomyService(AutonomyLevelStore repository,
                                  AutonomyChangeNotifier notifier,
                                  SamplingPolicy sampler,
                                  ObjectProvider<AuditApi> auditApi,
                                  ObjectMapper mapper) {
        this.repository = repository;
        this.notifier = notifier;
        this.sampler = sampler;
        this.auditApi = auditApi;
        this.mapper = mapper;
    }

    @Override
    public List<AutonomyView> list(String agentId) {
        List<AutonomyView> out = new ArrayList<>();
        for (TrustAutonomyDO row : repository.listByAgent(agentId)) {
            out.add(new AutonomyView(agentId, row.getActionClass(),
                    AutonomyLevel.fromCode(row.getLevel()).ordinalLevel(), null,
                    toInstant(row.getObserveUntil())));
        }
        return out;
    }

    @Override
    public int levelOf(String agentId, String actionClass) {
        TrustAutonomyDO row = repository.find(agentId, actionClass);
        return row == null ? 1 : AutonomyLevel.fromCode(row.getLevel()).ordinalLevel();
    }

    @Override
    public void setLevel(String agentId, String actionClass, AutonomyLevel level, String reason) {
        setLevel(agentId, actionClass, level, reason, "manual", "system");
    }

    /** @param actorKind manual（负责人手动）/ demote（TRU-10 消费联动）/ reset */
    public void setLevel(String agentId, String actionClass, AutonomyLevel level,
                         String reason, String actorKind, String actor) {
        TrustAutonomyDO current = repository.find(agentId, actionClass);
        String from = current == null ? AutonomyLevel.L1.name() : current.getLevel();
        TrustAutonomyDO row = current == null ? new TrustAutonomyDO() : current;
        row.setAgentId(agentId);
        row.setActionClass(actionClass);
        row.setLevel(level.name());
        repository.upsert(row);

        TrustAutonomyEventDO event = new TrustAutonomyEventDO();
        event.setId(Ids.newId("aev"));
        event.setAgentId(agentId);
        event.setActionClass(actionClass);
        event.setKind(kindOf(actorKind, from, level.name()));
        event.setFromLevel(from);
        event.setToLevel(level.name());
        event.setReason(reason);
        event.setActor(actor);
        repository.insertEvent(event);

        audit("trust.autonomy.level_changed", agentId, actionClass, Map.of(
                "from", from, "to", level.name(),
                "kind", event.getKind(), "reason", reason == null ? "" : reason));
        notifier.notifyChanged(null, agentId, actionClass, from, level.name(),
                reason == null ? "" : reason);
    }

    @Override
    public void markIncident(String agentId, String actionClass, String actionId, String note) {
        // 标记事故：直接降到 L1（ADR-034「降级」表）
        setLevel(agentId, actionClass, AutonomyLevel.L1,
                "incident: " + (note == null ? "" : note) + " (action=" + actionId + ")",
                "incident", "system");
    }

    @Override
    public ShadowReport shadowReport(String agentId) {
        // 影子上岗报告（TRU-11）在 M4；M3 恒空
        return ShadowReport.empty(agentId);
    }

    @Override
    public void recordAutoCommit(String agentId, String agentVersionId, String actionClass,
                                 int level, String actionId, String workspaceId) {
        bumpStat(agentId, actionClass);
        if (level >= 3 && sampler.isSampled(agentId, actionClass, actionId)) {
            TrustAutonomyEventDO event = new TrustAutonomyEventDO();
            event.setId(Ids.newId("aev"));
            event.setAgentId(agentId);
            event.setActionClass(actionClass);
            event.setKind("SAMPLING");
            event.setToLevel(AutonomyLevel.fromCode("L" + Math.min(level, 4)).name());
            event.setReason("post-hoc sampling (L3/L4)");
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("actionId", actionId);
            evidence.put("agentVersionId", agentVersionId == null ? "" : agentVersionId);
            evidence.put("workspaceId", workspaceId == null ? "" : workspaceId);
            evidence.put("reviewWindow", "undo_deadline");
            try {
                event.setEvidenceJson(mapper.writeValueAsString(evidence));
            } catch (Exception e) {
                event.setEvidenceJson("{}");
            }
            repository.insertEvent(event);
        }
    }

    private void bumpStat(String agentId, String actionClass) {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            TrustAutonomyStatsDO row = repository.findStats(agentId, actionClass, today);
            long n = 1;
            if (row != null) {
                n = readN(row.getStatsJson()) + 1;
            } else {
                row = new TrustAutonomyStatsDO();
                row.setAgentId(agentId);
                row.setActionClass(actionClass);
                row.setDay(today);
            }
            row.setStatsJson("{\"n\":" + n + "}");
            repository.upsertStats(row);
        } catch (RuntimeException e) {
            // 统计失败不阻断执行主链（TRU-12 汇总兜底）
            log.warn("trust_autonomy_stats 写入失败: {}", e.toString());
        }
    }

    private long readN(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return 0;
        }
        try {
            return mapper.readTree(statsJson).path("n").asLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String kindOf(String actorKind, String from, String to) {
        if ("incident".equals(actorKind)) {
            return "INCIDENT";
        }
        int f = parseLevel(from);
        int t = parseLevel(to);
        if ("demote".equals(actorKind) || t < f) {
            return "DEMOTE";
        }
        if (t > f) {
            return "PROMOTE";
        }
        return "RESET";
    }

    private static int parseLevel(String code) {
        try {
            return AutonomyLevel.fromCode(code).ordinalLevel();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private void audit(String action, String agentId, String actionClass, Map<String, Object> details) {
        AuditApi api = auditApi == null ? null : auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("agentId", agentId == null ? "" : agentId);
        all.put("actionClass", actionClass == null ? "" : actionClass);
        AuditEvent event = AuditEvent.builder()
                .ts(Instant.now())
                .action(action)
                .resourceType("trust_autonomy")
                .resourceId(agentId + ":" + actionClass)
                .outcome(AuditOutcome.SUCCESS)
                .details(all)
                .build();
        try {
            api.recordIndependent(event);
        } catch (RuntimeException e) {
            log.warn("autonomy 审计写入失败: {}", e.toString());
        }
    }

    private static java.time.Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
