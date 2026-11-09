package com.tioka.orchestration.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.orchestration.infrastructure.mapper.OrchDecisionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchEventMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchFactMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchMandateMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchSubscriptionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchWorkItemMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import com.tioka.orchestration.infrastructure.persistence.OrchFactDO;
import com.tioka.orchestration.infrastructure.persistence.OrchMandateDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchSubscriptionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 编排引擎读写（ORC-03）。越界：落在 infrastructure，供 ingest/decide/dispatch 调用。
 */
@Repository
public class OrchEngineRepository {
    private final OrchEventMapper events;
    private final OrchDecisionMapper decisions;
    private final OrchWorkItemMapper workItems;
    private final OrchMandateMapper mandates;
    private final OrchSubscriptionMapper subscriptions;
    private final OrchRunMapper runs;
    private final OrchFactMapper facts;
    private final OrchRuleMapper rules;
    private final OrchRuleVersionMapper versions;

    public OrchEngineRepository(OrchEventMapper events,
                                OrchDecisionMapper decisions,
                                OrchWorkItemMapper workItems,
                                OrchMandateMapper mandates,
                                OrchSubscriptionMapper subscriptions,
                                OrchRunMapper runs,
                                OrchFactMapper facts,
                                OrchRuleMapper rules,
                                OrchRuleVersionMapper versions) {
        this.events = events;
        this.decisions = decisions;
        this.workItems = workItems;
        this.mandates = mandates;
        this.subscriptions = subscriptions;
        this.runs = runs;
        this.facts = facts;
        this.rules = rules;
        this.versions = versions;
    }

    public Optional<String> findEventIdByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(events.findIdByIdempotencyKey(idempotencyKey));
    }

    public void insertEvent(OrchEventDO row) {
        events.insert(row);
    }

    public void updateEventDisposition(String eventId, String disposition) {
        OrchEventDO patch = new OrchEventDO();
        patch.setId(eventId);
        patch.setDisposition(disposition);
        events.updateById(patch);
    }

    public OrchEventDO findEvent(String id) {
        return events.selectById(id);
    }

    public void insertDecision(OrchDecisionDO row) {
        decisions.insert(row);
    }

    public List<OrchDecisionDO> listDecisionsByEvent(String eventId) {
        return decisions.selectList(Wrappers.<OrchDecisionDO>lambdaQuery()
                .eq(OrchDecisionDO::getEventId, eventId)
                .orderByAsc(OrchDecisionDO::getCreateAt));
    }

    public List<OrchDecisionDO> listDecisionsByRuleVersion(String ruleVersionId, String decision) {
        return decisions.selectList(Wrappers.<OrchDecisionDO>lambdaQuery()
                .eq(OrchDecisionDO::getRuleVersionId, ruleVersionId)
                .eq(decision != null, OrchDecisionDO::getDecision, decision)
                .orderByDesc(OrchDecisionDO::getCreateAt));
    }

    public OrchWorkItemDO findWorkItemByRuleAndSubject(String ruleId, String subjectKey) {
        return workItems.findByRuleAndSubject(ruleId, subjectKey);
    }

    public OrchWorkItemDO lockWorkItem(String ruleId, String subjectKey) {
        return workItems.lockByRuleAndSubject(ruleId, subjectKey);
    }

    public void insertWorkItem(OrchWorkItemDO row) {
        try {
            workItems.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发插入：调用方重读
            throw e;
        }
    }

    public void updateWorkItem(OrchWorkItemDO row) {
        workItems.updateLocked(row);
    }

    public OrchWorkItemDO findWorkItem(String id) {
        return workItems.selectById(id);
    }

    public void insertMandate(OrchMandateDO row) {
        mandates.insert(row);
    }

    public void updateMandate(OrchMandateDO row) {
        mandates.updateById(row);
    }

    public OrchMandateDO findLatestMandate(String workItemId) {
        return mandates.findLatestByWorkItem(workItemId);
    }

    public OrchSubscriptionDO findSubscription(String source, String externalThreadId) {
        return subscriptions.findBySourceAndThread(source, externalThreadId);
    }

    public void insertSubscription(OrchSubscriptionDO row) {
        subscriptions.insert(row);
    }

    public void insertRun(OrchRunDO row) {
        runs.insert(row);
    }

    public void updateRun(OrchRunDO row) {
        runs.updateById(row);
    }

    public OrchRunDO findRun(String id) {
        return runs.selectById(id);
    }

    public OrchRunDO lockRun(String id) {
        return runs.lockById(id);
    }

    public List<OrchRunDO> listRunsByWorkItem(String workItemId) {
        return runs.selectList(Wrappers.<OrchRunDO>lambdaQuery()
                .eq(OrchRunDO::getWorkItemId, workItemId)
                .orderByAsc(OrchRunDO::getCreateAt));
    }

    public int countRunsByWorkItem(String workItemId) {
        return runs.countByWorkItem(workItemId);
    }

    public int countNonTerminalBefore(String workItemId, LocalDateTime createAt) {
        return runs.countNonTerminalBefore(workItemId, createAt);
    }

    public int countDispatchedFacts(String runId) {
        return runs.countDispatchedFacts(runId);
    }

    public void appendFact(String runId, String kind, String detailJson) {
        OrchFactDO fact = new OrchFactDO();
        fact.setRunId(runId);
        fact.setSeq(facts.maxSeq(runId) + 1);
        fact.setKind(kind);
        fact.setDetailJson(detailJson);
        fact.setAt(LocalDateTime.now());
        facts.insert(fact);
    }

    public List<OrchFactDO> listFacts(String runId, long afterSeq) {
        return facts.selectList(Wrappers.<OrchFactDO>lambdaQuery()
                .eq(OrchFactDO::getRunId, runId)
                .gt(OrchFactDO::getSeq, afterSeq)
                .orderByAsc(OrchFactDO::getSeq));
    }

    public OrchRuleDO findRule(String id) {
        return rules.selectById(id);
    }

    public OrchRuleVersionDO findVersion(String id) {
        return versions.selectById(id);
    }

    /**
     * 命中 event_type 的规则头（稳定或灰度版本匹配；前缀 {@code .*} 支持）。
     */
    public List<OrchRuleDO> findActiveRulesForEventType(String eventType) {
        List<OrchRuleVersionDO> candidates = versions.selectList(Wrappers.<OrchRuleVersionDO>lambdaQuery()
                .in(OrchRuleVersionDO::getStage, List.of("STABLE", "CANARY")));
        Set<String> ruleIds = new LinkedHashSet<>();
        for (OrchRuleVersionDO v : candidates) {
            if (eventTypeMatches(v.getEventType(), eventType)) {
                ruleIds.add(v.getRuleId());
            }
        }
        List<OrchRuleDO> out = new ArrayList<>();
        for (String ruleId : ruleIds) {
            OrchRuleDO rule = rules.selectById(ruleId);
            if (rule == null) {
                continue;
            }
            // ORC-07 自动暂停：暂停期规则不参与派发
            if (rule.getPausedAt() != null) {
                continue;
            }
            // 须至少有稳定或灰度指针指向匹配版本
            boolean active = false;
            if (rule.getStableVersionId() != null) {
                OrchRuleVersionDO sv = versions.selectById(rule.getStableVersionId());
                active = sv != null && eventTypeMatches(sv.getEventType(), eventType);
            }
            if (!active && rule.getCanaryVersionId() != null) {
                OrchRuleVersionDO cv = versions.selectById(rule.getCanaryVersionId());
                active = cv != null && eventTypeMatches(cv.getEventType(), eventType);
            }
            if (active) {
                out.add(rule);
            }
        }
        return out;
    }

    static boolean eventTypeMatches(String pattern, String eventType) {
        if (pattern == null || eventType == null) {
            return false;
        }
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1); // keep trailing '.'
            return eventType.startsWith(prefix) || eventType.equals(pattern.substring(0, pattern.length() - 2));
        }
        return pattern.equals(eventType);
    }
}
