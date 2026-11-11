package tioka.pack.lease.cel;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LSE-04 八个 CEL 函数（M3-04 §1 L114；纯函数无副作用，domain-pack 权限仅 cpu）。
 * 经 {@code DomainPack.celFunctions()} 登记，由 RuleEvaluator 以 DYN 全局/成员重载挂接
 * （接收者作为第一参）。取值未知 → 返回 {@code null}：CEL 求值失败经 RuleEvaluator
 * 归为 EvalFailure → 结论「缺失」（三值语义，ADR-036 规则求值）。
 *
 * <p>SPI 约束（本批裁决：{@code CelFunction} 单参 {@code Function<Object,Object>} 不升级）下的口径偏差：
 * <ul>
 *   <li>{@code depositMonths(deposit, baseItem)} → 接收者自足：baseItem 由取值管线合入
 *       {@code deposit.baseMonthly}；</li>
 *   <li>{@code hasStatement(topic, type)} → 单参集合谓词：topic/type 由 CEL 表达式过滤，
 *       函数判定「过滤后的陈述集合非空」，如
 *       {@code hasStatement(m.statements.filter(s, s.topic == 'renewal.renew' && s.type == 'RIGHT'))}。</li>
 * </ul>
 */
public final class LeaseCelFunctions {

    private static final Pattern CURE = Pattern.compile("([0-9]+)\\s*(个工作日|工作日|日|天)");
    private static final Pattern CAP_MONTHS = Pattern.compile("(?:以|不超过|上限)\\s*([0-9.]+)\\s*个月?租金");
    private static final Pattern CAP_AMOUNT = Pattern.compile("(?:上限|不超过|最高)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(万元|元)");
    private static final Pattern OVERDUE_DAYS = Pattern.compile("(?:超过|连续|累计|达)\\s*([0-9]+)\\s*(?:日|天)");
    private static final Pattern OVERDUE_TIMES = Pattern.compile("(?:累计|年度|年累计)[^0-9]{0,6}([0-9]+)\\s*次");

    private LeaseCelFunctions() {
    }

    /** 费用项免租月数：{@code freeMonths(item)} / 成员形态 {@code item.freeMonths()}。 */
    public static Object freeMonths(Object item) {
        if (!(item instanceof Map<?, ?> m)) {
            return null;
        }
        // 成员形态 item.freeMonths()：接收者可以是 feeItem（abate.freeMonths）或 abate 单元本身
        Object v;
        if (m.containsKey("freeMonths")) {
            v = m.get("freeMonths");
        } else {
            Object abate = m.get("abate");
            if (!(abate instanceof Map<?, ?> a)) {
                return null;
            }
            v = a.get("freeMonths");
        }
        Double d = numOf(v instanceof Map<?, ?> c ? c.get("value") : v);
        return d == null ? null : longOrDouble(d);
    }

    /**
     * 费用项年化递增率：由 sched.segments 相邻段金额推得
     * rate = exp(Σ ln(后段/前段)) - 1（段跨度缺省按年近似）；段数 &lt; 2 或金额未知 → null。
     */
    public static Object escalationRate(Object item) {
        if (!(item instanceof Map<?, ?> m)) {
            return null;
        }
        Object sched = m.get("sched");
        Object segs = sched instanceof Map<?, ?> s ? s.get("segments") : null;
        if (!(segs instanceof List<?> list) || list.size() < 2) {
            return null;
        }
        double growth = 0;
        int gaps = 0;
        Double prev = null;
        for (Object o : list) {
            Double amount = numOf(o);
            if (amount == null || amount < 0) {
                return null;
            }
            if (prev != null && prev > 0 && amount > 0) {
                growth += Math.log(amount / prev);
                gaps++;
            }
            prev = amount;
        }
        if (gaps == 0) {
            return null;
        }
        double years = gaps; // 相邻段按年跨度近似（确定性口径）
        return Math.exp(growth / years) - 1;
    }

    /** 押金折合月数：{@code depositMonths(deposit)}（baseItem 经管线合入 baseMonthly）。 */
    public static Object depositMonths(Object deposit) {
        if (!(deposit instanceof Map<?, ?> d)) {
            return null;
        }
        Double amount = numOf(cellValue(d.get("amount"), "amount"));
        Double baseMonthly = numOf(d.get("baseMonthly"));
        if (amount == null || baseMonthly == null || baseMonthly <= 0) {
            return null;
        }
        return amount / baseMonthly;
    }

    /**
     * 陈述补救期天数：curePeriod / deadline 文本；「N 个工作日」按 7/5 换算成自然日并标待复核
     * （M3-04 L114：落码口径规格尚未确认）；「N 日 / N 天」直取。
     */
    public static Object cureDays(Object statement) {
        String text = firstText(statement, "curePeriod", "deadline");
        if (text == null) {
            return null;
        }
        Matcher m = CURE.matcher(text);
        if (!m.find()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        if (m.group(2).contains("工作日")) {
            return Math.ceil(n * 7 / 5d); // 待复核口径：工作日 → 自然日 7/5
        }
        return n;
    }

    /** 陈述违约金上限：quantum 文本「以 N 个月租金为限」→ 月数；「上限 N 元/万元」→ 金额。 */
    public static Object penaltyCap(Object statement) {
        String text = firstText(statement, "quantum");
        if (text == null) {
            return null;
        }
        Matcher months = CAP_MONTHS.matcher(text);
        if (months.find()) {
            return Double.parseDouble(months.group(1));
        }
        Matcher cap = CAP_AMOUNT.matcher(text);
        if (cap.find()) {
            double v = Double.parseDouble(cap.group(1).replace(",", ""));
            return "万元".equals(cap.group(2)) ? v * 10000 : v;
        }
        return null;
    }

    /**
     * 模型逾期解约天数门槛：termination 主题 RIGHT 陈述 trigger/condition 文本
     * 「超过/连续/累计/达 N 日」中的最小 N（最严门槛即触发线）；无 → null。
     */
    public static Object overdueDaysToTerminate(Object model) {
        Double min = null;
        for (Object s : statements(model, "RIGHT")) {
            String topic = textOf(s, "topic");
            if (topic == null || !topic.contains("termination")) {
                continue;
            }
            String text = firstText(s, "trigger", "condition", "quantum");
            if (text == null) {
                continue;
            }
            Matcher m = OVERDUE_DAYS.matcher(text);
            while (m.find()) {
                double v = Double.parseDouble(m.group(1));
                min = min == null ? v : Math.min(min, v);
            }
        }
        return min == null ? null : longOrDouble(min);
    }

    /** 模型逾期解约次数门槛：trigger「累计/年度 … N 次」中的最小 N；无 → null。 */
    public static Object overdueTimesToTerminate(Object model) {
        Double min = null;
        for (Object s : statements(model, "RIGHT")) {
            String topic = textOf(s, "topic");
            if (topic == null || !topic.contains("termination")) {
                continue;
            }
            String text = firstText(s, "trigger", "condition");
            if (text == null) {
                continue;
            }
            Matcher m = OVERDUE_TIMES.matcher(text);
            while (m.find()) {
                double v = Double.parseDouble(m.group(1));
                min = min == null ? v : Math.min(min, v);
            }
        }
        return min == null ? null : longOrDouble(min);
    }

    /**
     * 陈述集合谓词：过滤后的陈述集合非空（topic/type 由 CEL 表达式过滤，见类注释口径偏差）。
     * 例：{@code hasStatement(m.statements.filter(s, s.topic == 'renewal.renew' && s.type == 'RIGHT'))}。
     */
    public static Object hasStatement(Object filteredStatements) {
        if (filteredStatements instanceof List<?> list) {
            return !list.isEmpty();
        }
        return filteredStatements != null; // 单条陈述亦视为存在
    }

    // ── 工具 ─────────────────────────────────────────────────

    private static Object cellValue(Object cell, String fallbackKey) {
        if (cell instanceof Map<?, ?> c && c.containsKey("value")) {
            return c.get("value");
        }
        return cell == null ? null : cell; // 裸值或 null
    }

    private static Double numOf(Object v) {
        if (v instanceof Map<?, ?> m) {
            // 单元 {value: x} 或分段 {amount: x}
            if (m.containsKey("value")) {
                return numOf(m.get("value"));
            }
            if (m.containsKey("amount")) {
                return numOf(m.get("amount"));
            }
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 整数值返回 Long（CEL 比较稳定），否则 Double。 */
    private static Object longOrDouble(double d) {
        return d == Math.rint(d) ? (Object) (long) d : (Object) d;
    }

    private static String textOf(Object statement, String key) {
        if (!(statement instanceof Map<?, ?> m)) {
            return null;
        }
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static String firstText(Object statement, String... keys) {
        for (String k : keys) {
            String t = textOf(statement, k);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static List<Object> statements(Object model, String type) {
        if (!(model instanceof Map<?, ?> m) || !(m.get("statements") instanceof List<?> all)) {
            return List.of();
        }
        List<Object> out = new java.util.ArrayList<>();
        for (Object s : all) {
            String t = textOf(s, "type");
            if (t != null && t.equalsIgnoreCase(type)) {
                out.add(s);
            }
        }
        return out;
    }
}
