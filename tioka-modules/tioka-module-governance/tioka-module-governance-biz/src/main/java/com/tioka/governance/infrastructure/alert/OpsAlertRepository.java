package com.tioka.governance.infrastructure.alert;

import com.tioka.governance.infrastructure.mapper.OpsAlertEventMapper;
import com.tioka.governance.infrastructure.mapper.OpsAlertRuleMapper;
import com.tioka.governance.infrastructure.persistence.OpsAlertEventDO;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code ops_alert_rule} / {@code ops_alert_event} 仓储门面（GOV-13；infrastructure）。
 *
 * <p>application 只依赖本仓储，不直接持有 Mapper（PersistenceArchTest：application/kernel
 * 禁止直持 {@code ..infrastructure.mapper..}）。由 {@code AlertAutoConfiguration} 装配。
 */
public class OpsAlertRepository {

    private final OpsAlertRuleMapper rules;
    private final OpsAlertEventMapper events;

    public OpsAlertRepository(OpsAlertRuleMapper rules, OpsAlertEventMapper events) {
        this.rules = rules;
        this.events = events;
    }

    // ---- 规则 ----

    public List<OpsAlertRuleDO> listEnabledRules() {
        return rules.listEnabled();
    }

    public List<OpsAlertRuleDO> listAllRules() {
        return rules.listAll();
    }

    public Optional<OpsAlertRuleDO> findRule(String id) {
        return Optional.ofNullable(rules.selectById(id));
    }

    public Optional<OpsAlertRuleDO> findBuiltinRuleByName(String name) {
        return Optional.ofNullable(rules.findByBuiltinName(name));
    }

    public void insertRule(OpsAlertRuleDO row) {
        rules.insert(row);
    }

    public int updateRule(OpsAlertRuleDO row) {
        return rules.updateById(row);
    }

    public int deleteRule(String id) {
        return rules.deleteById(id);
    }

    public int touchLastFired(String id, LocalDateTime at) {
        return rules.touchLastFired(id, at);
    }

    // ---- 事件 ----

    public void insertEvent(OpsAlertEventDO row) {
        events.insert(row);
    }

    public Optional<OpsAlertEventDO> findLatestEvent(String ruleId, String targetId, String conditionType) {
        return Optional.ofNullable(events.findLatest(ruleId, targetId, conditionType));
    }

    public List<OpsAlertEventDO> listRecentEvents(String ruleId, String targetId, String state, int limit) {
        return events.listRecent(ruleId, targetId, state, limit);
    }

    public int markNotified(String id) {
        return events.markNotified(id);
    }
}
