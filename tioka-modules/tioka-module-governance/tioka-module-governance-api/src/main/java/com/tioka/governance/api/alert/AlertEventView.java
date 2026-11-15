package com.tioka.governance.api.alert;

import java.time.Instant;

/** 告警事件视图（GOV-13 / ADR-041 §5.3：事件入库，可查历史）。 */
public record AlertEventView(
        String id,
        String ruleId,
        String ruleName,
        AlertTargetType targetType,
        String targetId,
        AlertConditionType conditionType,
        AlertState state,
        AlertSeverity severity,
        Double metricValue,
        Double thresholdValue,
        String message,
        Instant observedAt) {
}
