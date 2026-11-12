package com.tioka.orchestration.application.decide;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.orchestration.api.engine.AcceptMode;
import com.tioka.orchestration.api.engine.AssignCommand;
import com.tioka.orchestration.api.engine.AssignResult;
import com.tioka.orchestration.api.engine.ConcurrencyMode;
import com.tioka.orchestration.api.engine.DecisionCode;
import com.tioka.orchestration.api.engine.DecisionView;
import com.tioka.orchestration.api.engine.MandateVia;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.CelRuleCondition;
import com.tioka.orchestration.application.RuleVersionPicker;
import com.tioka.orchestration.application.ingest.OrchAssignService;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchMandateDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchSubscriptionDO;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 单规则决定 + 跟进订阅短路（ORC-03）。
 */
@Service
public class OrchDecideService {
    private static final Logger log = LoggerFactory.getLogger(OrchDecideService.class);

    private final OrchEngineRepository repo;
    private final CelRuleCondition cel;
    private final OrchAssignService assignService;
    private final PrincipalLoaderApi principals;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final AgentSnapshotApi agentSnapshots;
    private final org.springframework.beans.factory.ObjectProvider<com.tioka.governance.api.charter.AgentCharterPort> charterPort;
    private final boolean recordIgnored;

    public OrchDecideService(OrchEngineRepository repo,
                             CelRuleCondition cel,
                             OrchAssignService assignService,
                             PrincipalLoaderApi principals,
                             com.fasterxml.jackson.databind.ObjectMapper mapper,
                             AgentSnapshotApi agentSnapshots,
                             org.springframework.beans.factory.ObjectProvider<com.tioka.governance.api.charter.AgentCharterPort> charterPort,
                             @Value("${tioka.orch.record-ignored:false}") boolean recordIgnored) {
        this.repo = repo;
        this.cel = cel;
        this.assignService = assignService;
        this.principals = principals;
        this.mapper = mapper;
        this.agentSnapshots = agentSnapshots;
        this.charterPort = charterPort;
        this.recordIgnored = recordIgnored;
    }

    public DecisionView decide(String eventId,
                               OrchApi.EventEnvelope event,
                               OrchRuleDO rule,
                               OrchApi.Strength eventStrength,
                               String workspaceId) {
        // 跟进订阅：命中则直接归单，强度视为 EXPLICIT
        FollowupHit followup = resolveFollowup(event);
        OrchApi.Strength strength = followup.hit()
                ? OrchApi.Strength.EXPLICIT
                : eventStrength;
        MandateVia via = StrengthResolver.viaFor(event.attrs(), followup.hit(), strength);

        if (followup.hit()) {
            OrchRuleVersionDO version = pickVersion(rule, event.subjectKey());
            if (version == null) {
                return record(eventId, null, strength, DecisionCode.UNAVAILABLE, "no_version", null);
            }
            return acceptAndAssign(eventId, event, rule, version, strength, via, workspaceId, followup.workItemId());
        }

        OrchRuleVersionDO version = pickVersion(rule, event.subjectKey());
        if (version == null) {
            return record(eventId, null, strength, DecisionCode.UNAVAILABLE, "no_version", null);
        }

        // 服务账号可用性
        try {
            loadServicePrincipal(version.getServiceAccountId(), workspaceId);
        } catch (BizException e) {
            return record(eventId, version.getId(), strength, DecisionCode.UNAVAILABLE,
                    "service_account_unavailable", null);
        }

        // 已有工单且 Mandate 已撤销 → STOP
        var existing = repo.findWorkItemByRuleAndSubject(rule.getId(), nullToEmpty(event.subjectKey()));
        if (existing != null) {
            OrchMandateDO mandate = repo.findLatestMandate(existing.getId());
            if (mandate != null && "REVOKED".equals(mandate.getStatus())) {
                return record(eventId, version.getId(), strength, DecisionCode.STOP, "mandate_revoked",
                        existing.getId());
            }
        }

        AcceptMode acceptMode = AcceptMode.fromWire(version.getAcceptMode());
        if (acceptMode == AcceptMode.OFF) {
            return maybeIgnore(eventId, version.getId(), strength, "accept_mode_off");
        }

        boolean matched;
        try {
            matched = cel.evaluate(version.getId(), version.getConditionCel(),
                    eventMap(event), event.attrs());
        } catch (CelRuleCondition.CelEvalException e) {
            // 灰度失败回落稳定
            if (version.getId().equals(rule.getCanaryVersionId()) && rule.getStableVersionId() != null) {
                log.warn("灰度 CEL 失败，回落稳定版 rule={} reason={}", rule.getId(), e.reason());
                OrchRuleVersionDO stable = repo.findVersion(rule.getStableVersionId());
                if (stable != null) {
                    version = stable;
                    acceptMode = AcceptMode.fromWire(version.getAcceptMode());
                    try {
                        matched = cel.evaluate(version.getId(), version.getConditionCel(),
                                eventMap(event), event.attrs());
                    } catch (CelRuleCondition.CelEvalException stableEx) {
                        return record(eventId, version.getId(), strength, DecisionCode.UNAVAILABLE,
                                stableEx.reason(), null);
                    }
                } else {
                    return record(eventId, version.getId(), strength, DecisionCode.UNAVAILABLE, e.reason(), null);
                }
            } else {
                return record(eventId, version.getId(), strength, DecisionCode.UNAVAILABLE, e.reason(), null);
            }
        }

        if (!matched) {
            return maybeIgnore(eventId, version.getId(), strength, "condition_false");
        }

        if (acceptMode == AcceptMode.OBSERVE && strength == OrchApi.Strength.INFERRED) {
            return record(eventId, version.getId(), strength, DecisionCode.WOULD_ACCEPT, "observe_inferred", null);
        }

        // observe+EXPLICIT 或 auto → ACCEPT
        return acceptAndAssign(eventId, event, rule, version, strength, via, workspaceId, null);
    }

    private DecisionView acceptAndAssign(String eventId,
                                         OrchApi.EventEnvelope event,
                                         OrchRuleDO rule,
                                         OrchRuleVersionDO version,
                                         OrchApi.Strength strength,
                                         MandateVia via,
                                         String workspaceId,
                                         String followupWorkItemId) {
        // TRU-03 / ADR-029 第 36 条：派工复验同时检查授权书状态为 ACTIVE
        String agentVersionId = firstAgentVersionId(version.getActionsJson());
        if (agentVersionId != null && !charterActive(agentVersionId)) {
            return record(eventId, version.getId(), strength, DecisionCode.UNAVAILABLE,
                    "charter_suspended", null);
        }
        Principal sa = loadServicePrincipal(version.getServiceAccountId(), workspaceId);
        Map<String, Object> scope = Map.of(
                "roles", sa.roles(),
                "permissions", sa.permissions(),
                "serviceAccountId", version.getServiceAccountId());

        AssignResult assign = assignService.assign(new AssignCommand(
                eventId,
                rule.getId(),
                version.getId(),
                nullToEmpty(event.subjectKey()),
                ConcurrencyMode.fromWire(version.getConcurrency()),
                strength,
                via,
                version.getServiceAccountId(),
                event.source(),
                event.attrs().get("tioka.requester_external_id"),
                event.attrs().get("tioka.requester_user_id"),
                scope,
                event.attrs(),
                event.body(),
                agentVersionId), workspaceId, followupWorkItemId);

        return record(eventId, version.getId(), strength, DecisionCode.ACCEPT, "accepted", assign.workItemId());
    }

    /** 解析规则版本 actions 中首个 agent 动作的 agentVersionId；无 Agent 动作返回 null。 */
    private String firstAgentVersionId(String actionsJson) {
        if (actionsJson == null || actionsJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode actions = mapper.readTree(actionsJson);
            if (actions.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode action : actions) {
                    String id = action.path("agentVersionId").asText(null);
                    if (id != null && !id.isBlank()) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析规则版本 actions 失败，跳过授权书复验: {}", e.toString());
        }
        return null;
    }

    /**
     * 派工复验（ADR-029 第 36 条 / ADR-032）：Agent 版本 → agent_agent.charter_status，
     * 经 {@code AgentCharterPort} 直连 DB 读（无快照缓存延迟）。非 ACTIVE（含 SUSPENDED /
     * HANDOVER）→ false → 决定 UNAVAILABLE(reason=charter_suspended)。快照 / 端口缺失
     * （TRU-01 特性关闭或离线）保持放行兼容。
     */
    private boolean charterActive(String agentVersionId) {
        var port = charterPort.getIfAvailable();
        if (port == null) {
            return true;
        }
        try {
            AgentSnapshotApi.AgentSnapshot snap = agentSnapshots.get(agentVersionId);
            String status = port.charterStatus(snap.agentId());
            return status == null || "ACTIVE".equals(status);
        } catch (BizException e) {
            log.warn("派工复验读取快照失败 agv={}: {}", agentVersionId, e.toString());
            return true;
        }
    }

    private DecisionView maybeIgnore(String eventId, String ruleVersionId,
                                     OrchApi.Strength strength, String reason) {
        if (!recordIgnored) {
            return new DecisionView(null, eventId, ruleVersionId, strength, DecisionCode.IGNORE, reason, null);
        }
        return record(eventId, ruleVersionId, strength, DecisionCode.IGNORE, reason, null);
    }

    private DecisionView record(String eventId, String ruleVersionId, OrchApi.Strength strength,
                                DecisionCode code, String reason, String workItemId) {
        if (code == DecisionCode.IGNORE && !recordIgnored) {
            return new DecisionView(null, eventId, ruleVersionId, strength, code, reason, workItemId);
        }
        String id = Ids.newId("od");
        OrchDecisionDO row = new OrchDecisionDO();
        row.setId(id);
        row.setEventId(eventId);
        row.setRuleVersionId(ruleVersionId == null ? "_" : ruleVersionId);
        row.setStrength(strength.name());
        row.setDecision(code.name());
        row.setReason(reason);
        row.setWorkItemId(workItemId);
        row.setCreateAt(LocalDateTime.now());
        repo.insertDecision(row);
        return new DecisionView(id, eventId, ruleVersionId, strength, code, reason, workItemId);
    }

    private OrchRuleVersionDO pickVersion(OrchRuleDO rule, String subjectKey) {
        Integer canaryPercent = null;
        if (rule.getCanaryVersionId() != null) {
            OrchRuleVersionDO canary = repo.findVersion(rule.getCanaryVersionId());
            if (canary != null) {
                canaryPercent = canary.getCanaryPercent();
            }
        }
        RuleVersionPicker.Pick pick = RuleVersionPicker.pick(
                rule.getStableVersionId(), rule.getCanaryVersionId(), canaryPercent, subjectKey);
        if (pick.versionId() == null) {
            return null;
        }
        return repo.findVersion(pick.versionId());
    }

    private FollowupHit resolveFollowup(OrchApi.EventEnvelope event) {
        String external = event.attrs().get(StrengthResolver.ATTR_EXTERNAL_THREAD);
        if (external == null || external.isBlank()) {
            return FollowupHit.miss();
        }
        OrchSubscriptionDO sub = repo.findSubscription(event.source(), external);
        if (sub == null) {
            return FollowupHit.miss();
        }
        if (sub.getUntilAt() != null && sub.getUntilAt().isBefore(LocalDateTime.now())) {
            return FollowupHit.miss();
        }
        return FollowupHit.hit(sub.getWorkItemId());
    }

    private Principal loadServicePrincipal(String serviceAccountId, String workspaceId) {
        try {
            long uid = Long.parseLong(serviceAccountId);
            return principals.load(uid, workspaceId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "service_account_id 非法");
        }
    }

    private static Map<String, Object> eventMap(OrchApi.EventEnvelope e) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", e.source());
        m.put("type", e.type());
        m.put("subjectKey", e.subjectKey());
        m.put("hops", e.hops());
        return m;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record FollowupHit(boolean hit, String workItemId) {
        static FollowupHit miss() { return new FollowupHit(false, null); }
        static FollowupHit hit(String workItemId) { return new FollowupHit(true, workItemId); }
    }
}
