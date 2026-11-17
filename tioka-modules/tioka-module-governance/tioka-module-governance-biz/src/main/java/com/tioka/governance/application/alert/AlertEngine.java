package com.tioka.governance.application.alert;

import com.tioka.governance.api.alert.AlertComparator;
import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertRuleRef;
import com.tioka.governance.api.alert.AlertSignalProvider.AlertObservation;
import com.tioka.governance.api.alert.AlertState;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GOV-13 纯求值逻辑（无 IO）：把规则 + 观测转换为待落库的告警判定。
 *
 * <p>状态语义（ADR-041 §5.3「去重与恢复」）：
 * <ul>
 *   <li>状态 / 指标条件：命中且上次非 FIRING → {@code FIRING}；未命中且上次为 {@code FIRING} → {@code RESOLVED}；否则无判定。</li>
 *   <li>事件条件（配置发布 / 回滚）：每次观测即一条 {@code FIRING}（知会，无恢复语义）。</li>
 * </ul>
 */
public final class AlertEngine {

    /** 一次求值产生的判定。 */
    public record Outcome(
            String targetId,
            AlertState state,
            Double metricValue,
            Double thresholdValue,
            String message,
            Instant observedAt) {
    }

    /**
     * 求值。
     *
     * @param rule           规则投影
     * @param observations   信号源返回的观测
     * @param lastStateByTarget 该规则各对象的上一条事件状态（{@code null} 表示无历史）
     */
    public List<Outcome> evaluate(AlertRuleRef rule,
                                 List<AlertObservation> observations,
                                 Map<String, String> lastStateByTarget) {
        List<Outcome> out = new ArrayList<>();
        if (observations == null) {
            return out;
        }
        boolean eventCondition = rule.conditionType().isEventCondition();
        for (AlertObservation obs : observations) {
            String targetId = obs.targetId() == null ? rule.targetId() : obs.targetId();
            if (eventCondition) {
                out.add(new Outcome(targetId, AlertState.FIRING, obs.value(), null,
                        message(rule, obs, true), obs.observedAt()));
                continue;
            }
            String last = lastStateByTarget == null ? null : lastStateByTarget.get(targetId);
            boolean firing = last != null && AlertState.FIRING.name().equals(last);
            boolean hit = triggers(rule, obs);
            if (hit) {
                if (!firing) {
                    out.add(new Outcome(targetId, AlertState.FIRING, obs.value(), rule.thresholdValue(),
                            message(rule, obs, true), obs.observedAt()));
                }
            } else if (firing) {
                out.add(new Outcome(targetId, AlertState.RESOLVED, obs.value(), rule.thresholdValue(),
                        message(rule, obs, false), obs.observedAt()));
            }
        }
        return out;
    }

    /** 单条观测是否命中规则条件。 */
    public static boolean triggers(AlertRuleRef rule, AlertObservation obs) {
        AlertConditionType type = rule.conditionType();
        if (type.isEventCondition()) {
            return true;
        }
        if (type.isStateCondition()) {
            return obs.state() != null && rule.thresholdText() != null
                    && obs.state().trim().equalsIgnoreCase(rule.thresholdText().trim());
        }
        // 指标条件
        AlertComparator comparator = rule.comparator();
        return obs.value() != null && comparator != null && rule.thresholdValue() != null
                && comparator.test(obs.value(), rule.thresholdValue());
    }

    /**
     * 静默时段判定：{@code start} 含、{@code end} 不含；跨零点（{@code start > end}）时按区间取反。
     * 任一为空视为无静默。
     */
    public static boolean inSilence(String start, String end, ZonedDateTime now) {
        LocalTime s = parse(start);
        LocalTime e = parse(end);
        if (s == null || e == null || now == null) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        if (s.equals(e)) {
            return false;
        }
        if (s.isBefore(e)) {
            return !t.isBefore(s) && t.isBefore(e);
        }
        return !t.isBefore(s) || t.isBefore(e);
    }

    /** 便捷重载：按给定时区判定。 */
    public static boolean inSilence(String start, String end, Instant now, ZoneId zone) {
        return inSilence(start, end, ZonedDateTime.ofInstant(now, zone));
    }

    private static LocalTime parse(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String message(AlertRuleRef rule, AlertObservation obs, boolean firing) {
        StringBuilder sb = new StringBuilder();
        sb.append(firing ? "命中" : "恢复").append('：').append(rule.name());
        sb.append("（").append(rule.targetType()).append(' ');
        sb.append(obs.targetId() == null ? rule.targetId() : obs.targetId()).append("）");
        sb.append(rule.conditionType());
        if (rule.thresholdText() != null) {
            sb.append(' ').append(rule.comparator() == null ? "=" : rule.comparator())
              .append(' ').append(rule.thresholdText());
        } else if (rule.thresholdValue() != null) {
            sb.append(' ').append(rule.comparator() == null ? "=" : rule.comparator())
              .append(' ').append(rule.thresholdValue());
        }
        if (obs.value() != null) {
            sb.append("，实测 ").append(obs.value());
        }
        if (obs.message() != null && !obs.message().isBlank()) {
            sb.append("；").append(obs.message());
        }
        return sb.toString();
    }
}
