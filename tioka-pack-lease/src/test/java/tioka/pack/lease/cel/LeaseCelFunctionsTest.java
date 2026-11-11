package tioka.pack.lease.cel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LSE-04 八个 CEL 函数：每函数 ≥3 例（取值 / 缺失 / 边界）。
 * 未知取值返回 null（RuleEvaluator 归为 EvalFailure → 结论缺失，三值语义）。
 */
class LeaseCelFunctionsTest {

    private static Map<String, Object> cell(Object value) {
        // value 可能为 null，Map.of 不允许 null 值
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> feeItem(Object freeMonths, Double... segmentAmounts) {
        Map<String, Object> abate = freeMonths == null ? Map.of() : Map.of("freeMonths", cell(freeMonths));
        List<Map<String, Object>> segments = List.of(segmentAmounts).stream()
                .map(a -> Map.<String, Object>of("amount", a))
                .toList();
        return Map.of("abate", abate, "sched", Map.of("segments", segments));
    }

    // ── freeMonths ───────────────────────────────────────────

    @Test
    void freeMonths_取值() {
        assertEquals(2L, LeaseCelFunctions.freeMonths(feeItem(2)));
        assertEquals(3.5, LeaseCelFunctions.freeMonths(feeItem(3.5)));
    }

    @Test
    void freeMonths_abate单元直取_成员形态() {
        // 成员形态 abate.freeMonths()：接收者为 abate 单元
        assertEquals(2L, LeaseCelFunctions.freeMonths(Map.of("freeMonths", cell(2))));
    }

    @Test
    void freeMonths_缺失返回null() {
        assertNull(LeaseCelFunctions.freeMonths(null));
        assertNull(LeaseCelFunctions.freeMonths(Map.of("abate", Map.of())));
        assertNull(LeaseCelFunctions.freeMonths(feeItem(null)));
    }

    // ── escalationRate ───────────────────────────────────────

    @Test
    void escalationRate_年递增5pct() {
        // 10000 → 10500（一年一段）→ rate = 0.05
        Object rate = LeaseCelFunctions.escalationRate(feeItem(null, 10000d, 10500d));
        assertEquals(0.05, (Double) rate, 1e-9);
    }

    @Test
    void escalationRate_两年复合() {
        // 10000 → 10500 → 11025：两段年跨度，ln 复合 → 年化 ≈ 5%
        Object rate = LeaseCelFunctions.escalationRate(feeItem(null, 10000d, 10500d, 11025d));
        assertEquals(0.05, (Double) rate, 1e-6);
    }

    @Test
    void escalationRate_缺失() {
        assertNull(LeaseCelFunctions.escalationRate(null));
        assertNull(LeaseCelFunctions.escalationRate(feeItem(null, 10000d)), "单段无递增");
        assertNull(LeaseCelFunctions.escalationRate(Map.of()), "无 sched");
    }

    // ── depositMonths ────────────────────────────────────────

    @Test
    void depositMonths_三个月押金() {
        Object months = LeaseCelFunctions.depositMonths(
                Map.of("amount", cell(30000d), "baseMonthly", 10000d));
        assertEquals(3.0, (Double) months, 1e-9);
    }

    @Test
    void depositMonths_缺失() {
        assertNull(LeaseCelFunctions.depositMonths(Map.of("amount", cell(30000d))), "baseMonthly 缺失");
        assertNull(LeaseCelFunctions.depositMonths(Map.of("amount", cell(null), "baseMonthly", 10000d)), "金额未知");
        assertNull(LeaseCelFunctions.depositMonths(null));
    }

    @Test
    void depositMonths_零基额缺失() {
        assertNull(LeaseCelFunctions.depositMonths(Map.of("amount", cell(30000d), "baseMonthly", 0d)));
    }

    // ── cureDays ─────────────────────────────────────────────

    @Test
    void cureDays_自然日直取() {
        assertEquals(15.0, LeaseCelFunctions.cureDays(Map.of("curePeriod", "承租方应于 15 日内补足")));
        assertEquals(7.0, LeaseCelFunctions.cureDays(Map.of("curePeriod", "7 天内修复")));
    }

    @Test
    void cureDays_工作日按7比5换算() {
        // 10 个工作日 → 14 自然日（7/5 换算，待复核口径）
        assertEquals(14.0, LeaseCelFunctions.cureDays(Map.of("curePeriod", "10 个工作日内")));
        // 3 个工作日 → 4.2 → 向上取整 5
        assertEquals(5.0, LeaseCelFunctions.cureDays(Map.of("curePeriod", "3 个工作日内")));
    }

    @Test
    void cureDays_缺失() {
        assertNull(LeaseCelFunctions.cureDays(Map.of("curePeriod", "双方另行协商")));
        assertNull(LeaseCelFunctions.cureDays(null));
    }

    // ── penaltyCap ───────────────────────────────────────────

    @Test
    void penaltyCap_月租口径() {
        assertEquals(3.0, LeaseCelFunctions.penaltyCap(
                Map.of("quantum", "违约金以 3 个月租金为限")));
    }

    @Test
    void penaltyCap_金额口径() {
        assertEquals(50000.0, LeaseCelFunctions.penaltyCap(
                Map.of("quantum", "违约金上限 5 万元")));
        assertEquals(5000.0, LeaseCelFunctions.penaltyCap(
                Map.of("quantum", "总额不超过 5,000 元")));
    }

    @Test
    void penaltyCap_缺失() {
        assertNull(LeaseCelFunctions.penaltyCap(Map.of("quantum", "按日千分之三")));
        assertNull(LeaseCelFunctions.penaltyCap(null));
    }

    // ── overdueDaysToTerminate / overdueTimesToTerminate ─────

    private static Map<String, Object> model(Map<String, Object>... statements) {
        return Map.of("statements", List.of(statements));
    }

    private static Map<String, Object> right(String topic, String trigger) {
        return Map.of("type", "RIGHT", "topic", topic, "trigger", trigger);
    }

    @Test
    void overdueDays_最严门槛() {
        Object days = LeaseCelFunctions.overdueDaysToTerminate(model(
                right("termination.landlord", "欠租超过 30 日"),
                right("termination.landlord", "累计超过 15 日催告仍不支付")));
        assertEquals(15L, days);
    }

    @Test
    void overdueDays_缺失() {
        assertNull(LeaseCelFunctions.overdueDaysToTerminate(model(
                right("rent.payment", "应于每月 5 日支付"))), "非终止主题不取");
        assertNull(LeaseCelFunctions.overdueDaysToTerminate(null));
    }

    @Test
    void overdueTimes_年度累计次数() {
        Object times = LeaseCelFunctions.overdueTimesToTerminate(model(
                right("termination.landlord", "年度累计 3 次逾期")));
        assertEquals(3L, times);
        assertNull(LeaseCelFunctions.overdueTimesToTerminate(model(
                right("termination.landlord", "欠租超过 30 日"))), "无次数表述");
    }

    // ── hasStatement ─────────────────────────────────────────

    @Test
    void hasStatement_非空集合为真() {
        assertEquals(true, LeaseCelFunctions.hasStatement(List.of(Map.of("topic", "renewal.renew"))));
    }

    @Test
    void hasStatement_空集合为假() {
        assertEquals(false, LeaseCelFunctions.hasStatement(List.of()));
    }

    @Test
    void hasStatement_单条陈述亦视为存在() {
        assertEquals(true, LeaseCelFunctions.hasStatement(Map.of("topic", "t")));
        assertFalse(Boolean.TRUE.equals(LeaseCelFunctions.hasStatement(null)));
    }
}
