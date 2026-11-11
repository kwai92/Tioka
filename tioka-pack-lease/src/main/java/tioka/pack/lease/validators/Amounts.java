package tioka.pack.lease.validators;

import tioka.pack.lease.LeasePackConfig;

import java.util.List;
import java.util.Map;

/**
 * V05 金额溯源核心：引用证据里能找到归属本项的金额事实，等值或按标准换算，容差 0.02。
 * 金额事实识别用确定性金额正则（阿拉伯数字 / 中文大写），换算表来自领域包配置
 * （{@code config/lease-pack.properties}：单位 × 元、期间 ÷ 月）。
 */
final class Amounts {

    /** M3-04 L97 规定的容差。 */
    static final double TOLERANCE = 0.02;

    private final LeasePackConfig config;

    Amounts(LeasePackConfig config) {
        this.config = config;
    }

    LeasePackConfig config() {
        return config;
    }

    /**
     * 溯源判定：value 能否在 sources 引文中找到等值（含换算）的金额事实。
     *
     * @param value   取值单元的金额（已归一化为 元/月）
     * @param sources 引文列表（quote / unit 可选）
     * @return true = 找到归属本项的金额事实
     */
    boolean traceable(Double value, List<Map<String, Object>> sources) {
        if (value == null || sources == null || sources.isEmpty()) {
            return false;
        }
        for (Map<String, Object> source : sources) {
            String quote = source.get("quote") == null ? null : source.get("quote").toString();
            Double fact = factAmountNormalized(quote);
            if (fact == null) {
                // sources 显式携带的 amount（抽取管线预归一）也作为事实
                fact = Drafts.asNumber(source.get("amount"));
            }
            if (fact != null && Math.abs(fact - value) <= TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 引文中金额事实按 元/月 归一化：
     * 「5万元/年」→ 50000/12；「50元/平方米/月」按整铺口径不变（单位换算表只归一金额与期间）；
     * 中文大写（壹万元整 → 10000，缺省月额口径）。
     */
    Double factAmountNormalized(String quote) {
        if (quote == null || quote.isBlank()) {
            return null;
        }
        // 1) 阿拉伯数字 + 可选单位/期间
        java.util.regex.Matcher num = java.util.regex.Pattern.compile(config.amountRegex()).matcher(quote);
        if (num.find()) {
            double base = Double.parseDouble(num.group().replace(",", ""));
            return normalize(base, quote.substring(num.end()));
        }
        // 2) 中文大写
        String cn = Drafts.firstCnAmount(quote);
        if (cn != null) {
            Double parsed = Drafts.parseCnAmount(cn);
            if (parsed != null) {
                return normalize(parsed, quote.substring(quote.indexOf(cn) + cn.length()));
            }
        }
        return null;
    }

    /** 依据金额后缀（如 万元/年）按换算表归一化到 元/月。 */
    private Double normalize(double base, String suffix) {
        double unitMult = 1.0;
        Double wan = config.unitMultiplier("万元");
        if (wan != null && suffix.contains("万元")) {
            unitMult = wan;
        }
        double periodDiv = 1.0;
        String afterUnit = unitMult > 1.0 ? suffix.substring(suffix.indexOf("万元") + 2) : suffix;
        if (afterUnit.startsWith("/")) {
            for (String period : new String[]{"年", "月", "日"}) {
                if (afterUnit.length() > 1 && afterUnit.charAt(1) == period.charAt(0)) {
                    Double months = config.periodMonths(period);
                    if (months != null && months > 0) {
                        periodDiv = months;
                    }
                    break;
                }
            }
        }
        return base * unitMult / periodDiv;
    }
}
