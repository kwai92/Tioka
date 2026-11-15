package com.tioka.governance.application.alert;

import com.tioka.governance.api.alert.AlertComparator;
import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertEventView;
import com.tioka.governance.api.alert.AlertRuleRef;
import com.tioka.governance.api.alert.AlertRuleView;
import com.tioka.governance.api.alert.AlertSeverity;
import com.tioka.governance.api.alert.AlertState;
import com.tioka.governance.api.alert.AlertTargetType;
import com.tioka.governance.infrastructure.persistence.OpsAlertEventDO;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 告警持久化类型与 API 视图 / 求值投影之间的转换（GOV-13）。 */
final class AlertViews {

    static AlertRuleView toView(OpsAlertRuleDO row) {
        return new AlertRuleView(
                row.getId(), row.getName(),
                parseTargetType(row.getTargetType()), row.getTargetId(),
                parseConditionType(row.getConditionType()), parseComparator(row.getComparator()),
                row.getThresholdValue(), row.getThresholdText(),
                row.getWindowMinutes() == null ? 10 : row.getWindowMinutes(),
                parseSeverity(row.getSeverity()),
                splitCsv(row.getChannels()), splitCsv(row.getRecipients()),
                row.getSilenceStart(), row.getSilenceEnd(),
                Boolean.TRUE.equals(row.getEnabled()), Boolean.TRUE.equals(row.getBuiltin()),
                toInstant(row.getLastFiredAt()), row.getDescription(),
                toInstant(row.getCreateAt()), toInstant(row.getUpdateAt()),
                row.getCreateBy(), row.getUpdateBy());
    }

    static AlertEventView toEventView(OpsAlertEventDO row) {
        return new AlertEventView(
                row.getId(), row.getRuleId(), row.getRuleName(),
                parseTargetType(row.getTargetType()), row.getTargetId(),
                parseConditionType(row.getConditionType()), parseState(row.getState()),
                parseSeverity(row.getSeverity()), row.getMetricValue(), row.getThresholdValue(),
                row.getMessage(), toInstant(row.getObservedAt()));
    }

    static AlertRuleRef toRef(OpsAlertRuleDO row) {
        return new AlertRuleRef(
                row.getId(), row.getName(),
                parseTargetType(row.getTargetType()), row.getTargetId(),
                parseConditionType(row.getConditionType()), parseComparator(row.getComparator()),
                row.getThresholdValue(), row.getThresholdText(),
                row.getWindowMinutes() == null ? 10 : row.getWindowMinutes(),
                toInstant(row.getLastFiredAt()));
    }

    static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    static String joinCsv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    static AlertTargetType parseTargetType(String value) {
        return value == null ? null : AlertTargetType.valueOf(value.trim().toUpperCase());
    }

    static AlertConditionType parseConditionType(String value) {
        return value == null ? null : AlertConditionType.valueOf(value.trim().toUpperCase());
    }

    static AlertComparator parseComparator(String value) {
        return value == null ? null : AlertComparator.valueOf(value.trim().toUpperCase());
    }

    static AlertSeverity parseSeverity(String value) {
        return value == null ? null : AlertSeverity.valueOf(value.trim().toUpperCase());
    }

    static AlertState parseState(String value) {
        return value == null ? null : AlertState.valueOf(value.trim().toUpperCase());
    }

    private AlertViews() {
    }
}
