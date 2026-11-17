package com.tioka.governance.api.alert;

import java.time.Instant;

/**
 * 求值期规则投影（GOV-13 / ADR-041 §5.3）：{@link AlertSignalProvider} 只依赖本投影，
 * 不依赖 {@code ops_alert_rule} 持久化类型（保持模块边界）。
 */
public record AlertRuleRef(
        String id,
        String name,
        AlertTargetType targetType,
        String targetId,
        AlertConditionType conditionType,
        AlertComparator comparator,
        Double thresholdValue,
        String thresholdText,
        int windowMinutes,
        Instant lastFiredAt) {
}
