package com.tioka.governance.application.alert;

import com.tioka.governance.api.alert.AlertComparator;
import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertRuleCommand;
import com.tioka.governance.api.alert.AlertSeverity;
import com.tioka.governance.api.alert.AlertTargetType;

import java.util.List;

/**
 * GOV-13 内置默认告警规则（ADR-041 §5.3「内置默认规则」）。
 *
 * <p>幂等播种：按名字匹配 {@code builtin=TRUE} 的行，缺失才插入。
 * <ul>
 *   <li>供应商 DOWN；</li>
 *   <li>任一供给侧配额达到 80% / 100%；</li>
 *   <li>10 分钟内故障接力率超过 20%；</li>
 *   <li>结构化输出首次通过率 7 日均值下降超过 5 个百分点、失败率超过 1%（ADR-066 §10.1）。</li>
 * </ul>
 */
public final class BuiltinAlertRules {

    /** 供应商健康状态变更（MH-10 提供信号源；未接线时不产生事件）。 */
    public static final String NAME_VENDOR_DOWN = "供应商 DOWN";
    /** 供给侧配额达到预警比例。 */
    public static final String NAME_QUOTA_WARN = "任一供给侧配额达到 80%";
    /** 供给侧配额用完。 */
    public static final String NAME_QUOTA_EXHAUSTED = "任一供给侧配额达到 100%";
    /** 故障接力率过高。 */
    public static final String NAME_FAILOVER_RATE = "10 分钟内故障接力率超过 20%";
    /** 结构化输出首次通过率下降。 */
    public static final String NAME_STRUCTURED_FIRST_PASS_DROP = "结构化输出首次通过率 7 日均值下降超过 5 个百分点";
    /** 结构化输出失败率过高。 */
    public static final String NAME_STRUCTURED_FAILURE_RATE = "结构化输出失败率超过 1%";

    /** 缺省统计窗口（分钟）：7 天（ADR-066 §10.1）。 */
    private static final int WEEK_MINUTES = 7 * 24 * 60;

    public static List<AlertRuleCommand> defaults() {
        return List.of(
                new AlertRuleCommand(
                        NAME_VENDOR_DOWN, AlertTargetType.VENDOR, null,
                        AlertConditionType.HEALTH_STATUS, AlertComparator.EQ,
                        null, "DOWN", 0, AlertSeverity.CRITICAL,
                        List.of("in_app"), List.of(), null, null, true,
                        "供应商健康状态变为 DOWN（ADR-041 §5.3）"),
                new AlertRuleCommand(
                        NAME_QUOTA_WARN, AlertTargetType.ALL, null,
                        AlertConditionType.QUOTA_WARN, AlertComparator.GTE,
                        0.8, null, 0, AlertSeverity.WARNING,
                        List.of("in_app"), List.of(), null, null, true,
                        "任一供给侧配额达到预警比例 80%（ADR-041 §5.3）"),
                new AlertRuleCommand(
                        NAME_QUOTA_EXHAUSTED, AlertTargetType.ALL, null,
                        AlertConditionType.QUOTA_EXHAUSTED, AlertComparator.GTE,
                        1.0, null, 0, AlertSeverity.CRITICAL,
                        List.of("in_app"), List.of(), null, null, true,
                        "任一供给侧配额用完（ADR-041 §5.3）"),
                new AlertRuleCommand(
                        NAME_FAILOVER_RATE, AlertTargetType.ALL, null,
                        AlertConditionType.FAILOVER_RATE, AlertComparator.GT,
                        0.2, null, 10, AlertSeverity.WARNING,
                        List.of("in_app"), List.of(), null, null, true,
                        "10 分钟内故障接力率超过 20%（ADR-041 §5.3）"),
                new AlertRuleCommand(
                        NAME_STRUCTURED_FIRST_PASS_DROP, AlertTargetType.ALL, null,
                        AlertConditionType.STRUCTURED_FIRST_PASS_DROP, AlertComparator.GT,
                        5.0, null, WEEK_MINUTES, AlertSeverity.WARNING,
                        List.of("in_app"), List.of(), null, null, true,
                        "某（Schema、模型）首次通过率 7 日均值下降超过 5 个百分点（ADR-066 §10.1）"),
                new AlertRuleCommand(
                        NAME_STRUCTURED_FAILURE_RATE, AlertTargetType.ALL, null,
                        AlertConditionType.STRUCTURED_FAILURE_RATE, AlertComparator.GT,
                        0.01, null, WEEK_MINUTES, AlertSeverity.WARNING,
                        List.of("in_app"), List.of(), null, null, true,
                        "某（Schema、模型）结构化输出失败率超过 1%（ADR-066 §10.1）"));
    }

    private BuiltinAlertRules() {
    }
}
