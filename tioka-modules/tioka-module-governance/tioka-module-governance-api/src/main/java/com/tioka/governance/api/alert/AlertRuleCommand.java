package com.tioka.governance.api.alert;

import java.util.List;

/** 告警规则创建 / 更新入参（GOV-13 / ADR-041 §5.3）。 */
public record AlertRuleCommand(
        String name,
        AlertTargetType targetType,
        String targetId,
        AlertConditionType conditionType,
        AlertComparator comparator,
        Double thresholdValue,
        String thresholdText,
        int windowMinutes,
        AlertSeverity severity,
        List<String> channels,
        List<String> recipients,
        String silenceStart,
        String silenceEnd,
        boolean enabled,
        String description) {
}
