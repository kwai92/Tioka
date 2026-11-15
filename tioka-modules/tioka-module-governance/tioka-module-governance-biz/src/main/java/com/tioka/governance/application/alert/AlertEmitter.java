package com.tioka.governance.application.alert;

import com.tioka.framework.id.Ids;
import com.tioka.governance.api.alert.AlertState;
import com.tioka.governance.infrastructure.alert.OpsAlertRepository;
import com.tioka.governance.infrastructure.persistence.OpsAlertEventDO;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;
import com.tioka.system.api.notify.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警事件落库与通知（GOV-13 / ADR-041 §5.3「去重与恢复」）。
 *
 * <p>去重：同一规则对同一对象只在状态变化时写事件并发通知；恢复时发恢复通知；
 * 静默时段内只入事件、不外发通知。
 */
public class AlertEmitter {

    private static final Logger log = LoggerFactory.getLogger(AlertEmitter.class);
    private static final String TEMPLATE_FIRING = "alert.firing";
    private static final String TEMPLATE_RESOLVED = "alert.resolved";

    private final OpsAlertRepository repo;
    private final ObjectProvider<NotificationPort> notifications;
    private final AlertProperties props;

    public AlertEmitter(OpsAlertRepository repo,
                        ObjectProvider<NotificationPort> notifications,
                        AlertProperties props) {
        this.repo = repo;
        this.notifications = notifications;
        this.props = props;
    }

    /**
     * 落库一条判定；返回是否真正发出（状态变化）。无状态变化时不写事件。
     *
     * @param eventLike 事件型条件（配置发布 / 回滚）：不做状态去重，每次观测各发一条
     */
    public boolean emit(OpsAlertRuleDO rule, AlertEngine.Outcome outcome, boolean eventLike) {
        String targetId = outcome.targetId() == null ? rule.getTargetId() : outcome.targetId();
        Optional<OpsAlertEventDO> latest =
                repo.findLatestEvent(rule.getId(), targetId, rule.getConditionType());
        String lastState = latest.map(OpsAlertEventDO::getState).orElse(null);

        if (!eventLike) {
            if (lastState == null && outcome.state() == AlertState.RESOLVED) {
                return false;
            }
            if (outcome.state().name().equals(lastState)) {
                return false;
            }
        }

        String dedupKey = dedupKey(rule.getId(), targetId, rule.getConditionType(), outcome.state());
        boolean silenced = AlertEngine.inSilence(rule.getSilenceStart(), rule.getSilenceEnd(),
                ZonedDateTime.ofInstant(outcome.observedAt(), zone()));

        OpsAlertEventDO event = new OpsAlertEventDO();
        event.setId(Ids.newId("alev"));
        event.setRuleId(rule.getId());
        event.setRuleName(rule.getName());
        event.setTargetType(rule.getTargetType());
        event.setTargetId(targetId);
        event.setConditionType(rule.getConditionType());
        event.setState(outcome.state().name());
        event.setSeverity(rule.getSeverity());
        event.setMetricValue(outcome.metricValue());
        event.setThresholdValue(outcome.thresholdValue() == null
                ? rule.getThresholdValue() : outcome.thresholdValue());
        event.setMessage(outcome.message());
        event.setDedupKey(dedupKey);
        event.setNotified(false);
        event.setObservedAt(AlertViews.toUtc(outcome.observedAt()));
        event.setCreateAt(AlertViews.toUtc(Instant.now()));
        repo.insertEvent(event);

        // 事件型条件推进游标水位（避免下次重复读取同一配置变更）
        if (eventLike) {
            repo.touchLastFired(rule.getId(), AlertViews.toUtc(outcome.observedAt()));
        }

        if (silenced) {
            return true;
        }
        if (notify(rule, event, outcome)) {
            repo.markNotified(event.getId());
        }
        return true;
    }

    /** 外发通知：每条渠道 × 每个接收人各一条；返回是否有任一条成功投递。 */
    private boolean notify(OpsAlertRuleDO rule, OpsAlertEventDO event, AlertEngine.Outcome outcome) {
        NotificationPort port = notifications == null ? null : notifications.getIfAvailable();
        if (port == null) {
            return false;
        }
        List<String> recipients = AlertViews.splitCsv(rule.getRecipients());
        if (recipients.isEmpty()) {
            recipients = props.getDefaultRecipients();
        }
        if (recipients.isEmpty()) {
            return false;
        }
        List<String> channels = AlertViews.splitCsv(rule.getChannels());
        if (channels.isEmpty()) {
            channels = List.of("in_app");
        }
        String template = outcome.state() == AlertState.FIRING ? TEMPLATE_FIRING : TEMPLATE_RESOLVED;
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("alertId", event.getId());
        variables.put("ruleId", rule.getId());
        variables.put("ruleName", rule.getName());
        variables.put("targetType", rule.getTargetType());
        variables.put("targetId", event.getTargetId());
        variables.put("conditionType", rule.getConditionType());
        variables.put("severity", rule.getSeverity());
        variables.put("state", outcome.state().name());
        variables.put("metricValue", outcome.metricValue());
        variables.put("thresholdValue", event.getThresholdValue());
        variables.put("message", event.getMessage());
        variables.put("observedAt", String.valueOf(outcome.observedAt()));

        boolean delivered = false;
        for (String channel : channels) {
            for (String recipient : recipients) {
                try {
                    port.notify(new NotificationPort.NotificationCommand(
                            channel, recipient, template, variables, event.getDedupKey()));
                    delivered = true;
                } catch (RuntimeException e) {
                    log.warn("告警通知失败（忽略）channel={} recipient={}: {}", channel, recipient, e.toString());
                }
            }
        }
        return delivered;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(props.getSilenceZone());
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    static String dedupKey(String ruleId, String targetId, String conditionType, AlertState state) {
        return ruleId + ":" + (targetId == null ? "-" : targetId) + ":" + conditionType + ":" + state;
    }
}
