package com.tioka.governance.application.alert;

import com.tioka.governance.api.alert.AlertComparator;
import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertRuleRef;
import com.tioka.governance.api.alert.AlertSignalProvider.AlertObservation;
import com.tioka.governance.api.alert.AlertState;
import com.tioka.governance.api.alert.AlertTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GOV-13 纯求值逻辑离线验收（ADR-041 §5.3）：状态 / 指标 / 事件条件 + 去重与恢复 + 静默时段。 */
class AlertEngineTest {

    private static final Instant AT = Instant.parse("2026-11-15T04:00:00Z");
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private final AlertEngine engine = new AlertEngine();

    @Test
    @DisplayName("状态条件：无历史命中 → FIRING；已 FIRING 再命中 → 去重；未命中 → RESOLVED")
    void stateConditionLifecycle() {
        AlertRuleRef rule = rule(AlertConditionType.HEALTH_STATUS, AlertComparator.EQ,
                null, "DOWN", AlertTargetType.VENDOR, "prov_1");

        List<AlertEngine.Outcome> fired = engine.evaluate(rule,
                List.of(state("prov_1", "DOWN")), Map.of());
        assertEquals(1, fired.size());
        assertEquals(AlertState.FIRING, fired.get(0).state());
        assertEquals("prov_1", fired.get(0).targetId());

        List<AlertEngine.Outcome> deduped = engine.evaluate(rule,
                List.of(state("prov_1", "DOWN")), Map.of("prov_1", "FIRING"));
        assertTrue(deduped.isEmpty(), "同状态重复命中不得再发");

        List<AlertEngine.Outcome> resolved = engine.evaluate(rule,
                List.of(state("prov_1", "UP")), Map.of("prov_1", "FIRING"));
        assertEquals(1, resolved.size());
        assertEquals(AlertState.RESOLVED, resolved.get(0).state());

        assertTrue(engine.evaluate(rule, List.of(state("prov_1", "UP")), Map.of()).isEmpty(),
                "从未触发过时不得发恢复");
    }

    @Test
    @DisplayName("指标条件：越阈值 → FIRING；回落到阈值内且已 FIRING → RESOLVED")
    void metricConditionLifecycle() {
        AlertRuleRef rule = rule(AlertConditionType.QUOTA_WARN, AlertComparator.GTE,
                0.8, null, AlertTargetType.ALL, null);

        List<AlertEngine.Outcome> fired = engine.evaluate(rule,
                List.of(value("ws_1", 0.9)), Map.of());
        assertEquals(1, fired.size());
        assertEquals(AlertState.FIRING, fired.get(0).state());
        assertEquals(0.8, fired.get(0).thresholdValue());

        List<AlertEngine.Outcome> resolved = engine.evaluate(rule,
                List.of(value("ws_1", 0.5)), Map.of("ws_1", "FIRING"));
        assertEquals(1, resolved.size());
        assertEquals(AlertState.RESOLVED, resolved.get(0).state());
    }

    @Test
    @DisplayName("事件条件（配置发布 / 回滚）：每次观测各发一条 FIRING，无去重")
    void eventConditionAlwaysFires() {
        AlertRuleRef rule = rule(AlertConditionType.CONFIG_PUBLISHED, AlertComparator.EQ,
                null, null, AlertTargetType.ALL, null);

        List<AlertEngine.Outcome> out = engine.evaluate(rule,
                List.of(event("model_binding", "binding_1"), event("model_binding", "binding_2")),
                Map.of("binding_1", "FIRING"));
        assertEquals(2, out.size(), "事件条件不受历史状态影响");
        assertTrue(out.stream().allMatch(o -> o.state() == AlertState.FIRING));
    }

    @Test
    @DisplayName("ALL 规则逐对象展开：各对象独立去重")
    void allTargetExpandsPerObject() {
        AlertRuleRef rule = rule(AlertConditionType.QUOTA_EXHAUSTED, AlertComparator.GTE,
                1.0, null, AlertTargetType.ALL, null);

        List<AlertEngine.Outcome> out = engine.evaluate(rule,
                List.of(value("ws_1", 1.0), value("ws_2", 0.3)),
                Map.of("ws_1", "FIRING"));
        assertTrue(out.isEmpty(), "ws_1 已 FIRING，ws_2 未越阈值");
    }

    @Test
    @DisplayName("triggers：状态串不区分大小写；指标按比较符；观测缺值不算命中")
    void triggersSemantics() {
        AlertRuleRef statusRule = rule(AlertConditionType.HEALTH_STATUS, AlertComparator.EQ,
                null, "down", AlertTargetType.VENDOR, "p");
        assertTrue(AlertEngine.triggers(statusRule, state("p", "DOWN")));
        assertFalse(AlertEngine.triggers(statusRule, state("p", "UP")));
        assertFalse(AlertEngine.triggers(statusRule, new AlertObservation("p", null, null, null, AT)));

        AlertRuleRef metricRule = rule(AlertConditionType.P95_LATENCY, AlertComparator.GT,
                500.0, null, AlertTargetType.VENDOR, "p");
        assertTrue(AlertEngine.triggers(metricRule, value("p", 800)));
        assertFalse(AlertEngine.triggers(metricRule, value("p", 500)));
    }

    @Test
    @DisplayName("静默时段：同日常规窗口 / 跨零点窗口 / 起止相等 / 缺值")
    void silenceWindows() {
        assertTrue(AlertEngine.inSilence("09:00", "18:00", at("12:00"), SH));
        assertFalse(AlertEngine.inSilence("09:00", "18:00", at("20:00"), SH));
        assertFalse(AlertEngine.inSilence("09:00", "18:00", at("09:00").minusSeconds(1), SH));

        assertTrue(AlertEngine.inSilence("22:00", "06:00", at("23:30"), SH));
        assertTrue(AlertEngine.inSilence("22:00", "06:00", at("03:00"), SH));
        assertFalse(AlertEngine.inSilence("22:00", "06:00", at("12:00"), SH));

        assertFalse(AlertEngine.inSilence("10:00", "10:00", at("10:00"), SH), "起止相等视为无静默");
        assertFalse(AlertEngine.inSilence(null, "18:00", at("12:00"), SH));
        assertFalse(AlertEngine.inSilence("09:00", null, at("12:00"), SH));
    }

    private static Instant at(String hhmm) {
        return ZonedDateTime.of(java.time.LocalDate.of(2026, 11, 15),
                java.time.LocalTime.parse(hhmm), SH).toInstant();
    }

    private static AlertRuleRef rule(AlertConditionType condition, AlertComparator comparator,
                                     Double value, String text, AlertTargetType targetType,
                                     String targetId) {
        return new AlertRuleRef("alrt_1", "测试规则", targetType, targetId,
                condition, comparator, value, text, 10, null);
    }

    private static AlertObservation state(String targetId, String state) {
        return AlertObservation.ofState(targetId, state, null, AT);
    }

    private static AlertObservation value(String targetId, double v) {
        return AlertObservation.ofValue(targetId, v, null, AT);
    }

    private static AlertObservation event(String targetId, String message) {
        return new AlertObservation(targetId, null, null, message, AT);
    }
}
