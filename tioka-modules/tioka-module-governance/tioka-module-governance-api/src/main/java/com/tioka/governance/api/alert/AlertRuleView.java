package com.tioka.governance.api.alert;

import java.time.Instant;
import java.util.List;

/**
 * 告警规则视图（GOV-13 / ADR-041 §5.3）。
 *
 * @param channels     通知渠道列表，如 {@code [in_app, im:feishu, email]}
 * @param recipients   接收人列表；空表示用系统缺省接收人
 * @param silenceStart 静默时段起（{@code HH:mm}，含）；可空
 * @param silenceEnd   静默时段止（{@code HH:mm}，不含）；可空
 * @param lastFiredAt  事件型条件的游标水位（仅取该时刻之后新发生的配置变更）
 */
public record AlertRuleView(
        String id,
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
        boolean builtin,
        Instant lastFiredAt,
        String description,
        Instant createAt,
        Instant updateAt,
        String createBy,
        String updateBy) {
}
