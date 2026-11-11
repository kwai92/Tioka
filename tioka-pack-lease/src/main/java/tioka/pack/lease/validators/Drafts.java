package tioka.pack.lease.validators;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 草稿 Map 结构访问助手（SPI 入参 {@code Map<String,Object> draft} 的领域内约定）：
 *
 * <pre>
 * draft = {
 *   contracts: [ {kind, contractNo, signDate, effectiveDate} ],
 *   subjects:  { "S1": {code, area: cell, ...} },
 *   feeItems:  { "RENT@S1": {feeCode, subject, calc:{baseAmount: cell}, sched:{segments:[...]}, pay:{...}, tax:{rate: cell}, abate:{freeMonths: cell, condition: cell}} },
 *   deposits:  { "保证金@S1": {depositType, subject, amount: cell} },
 *   modifications: [ {type, target, effectiveDate: cell, sources:[...]} ],
 *   documentUnits: [ {unitId, text} ],  excludedUnits: [unitId],
 *   priorDepositSourceIds: [unitId]
 * }
 * cell = { value, status, sources: [ {unitId, quote, amount?, unit?, role?} ] }
 * status ∈ KNOWN / DERIVED / DEFAULT / INHERITED / UNKNOWN / NOT_APPLICABLE / BLANK / REVIEW
 * </pre>
 *
 * 全部访问空安全；不抛错（校验器是纯函数，坏结构返回空集）。
 */
final class Drafts {

    private static final Pattern AMOUNT = Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]+)?");
    private static final Pattern CN_AMOUNT = Pattern.compile("[零壹贰叁肆伍陆柒捌玖拾佰仟万亿万元角分整]+");

    private Drafts() {
    }

    /** 顶层键取 Map。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object owner, String key) {
        if (!(owner instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /** 对象本身即 Map 时原样取用（ List 元素等场景）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> objMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /** 顶层键取 List。 */
    @SuppressWarnings("unchecked")
    static List<Object> list(Object owner, String key) {
        if (!(owner instanceof Map<?, ?> m)) {
            return List.of();
        }
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    /** 点路径取值：{@code calc.baseAmount}。 */
    static Object path(Object owner, String dottedPath) {
        Object cur = owner;
        for (String part : dottedPath.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
        }
        return cur;
    }

    /** 点路径取取值单元（cell）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> cell(Object owner, String dottedPath) {
        Object v = path(owner, dottedPath);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /** 取值单元的 value。 */
    static Object cellValue(Map<String, Object> cell) {
        return cell.get("value");
    }

    /** 取值单元的 status（大写归一）。 */
    static String cellStatus(Map<String, Object> cell) {
        Object s = cell.get("status");
        return s == null ? null : s.toString().toUpperCase();
    }

    /** 取值单元的 sources 列表。 */
    static List<Map<String, Object>> sources(Map<String, Object> cell) {
        Object s = cell.get("sources");
        if (!(s instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) o;
                out.add(src);
            }
        }
        return out;
    }

    static Double asNumber(Object v) {
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

    /** quote 里的阿拉伯数字金额（首个；千分位去除）。 */
    static Double firstArabicAmount(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = AMOUNT.matcher(text);
        return m.find() ? Double.valueOf(m.group().replace(",", "")) : null;
    }

    /** quote 里的中文大写金额字符串（首个）。 */
    static String firstCnAmount(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = CN_AMOUNT.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** 数字字符对应数值。 */
    private static double digitValue(char c) {
        return switch (c) {
            case '壹' -> 1;
            case '贰' -> 2;
            case '叁' -> 3;
            case '肆' -> 4;
            case '伍' -> 5;
            case '陆' -> 6;
            case '柒' -> 7;
            case '捌' -> 8;
            case '玖' -> 9;
            default -> 0;
        };
    }

    /** 中文大写金额解析（元；支持 仟佰拾万亿 与 角分整）。解析不出返回 null。 */
    static Double parseCnAmount(String cn) {
        if (cn == null || cn.isEmpty()) {
            return null;
        }
        double yuan = 0;
        double section = 0; // 万/亿 段内累计
        double digit = 0;
        boolean sawUnit = false;
        for (int i = 0; i < cn.length(); i++) {
            char c = cn.charAt(i);
            switch (c) {
                case '零' -> digit = 0;
                case '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖' -> digit = digitValue(c);
                case '拾' -> {
                    section += (digit == 0 ? 1 : digit) * 10;
                    digit = 0;
                }
                case '佰' -> {
                    section += (digit == 0 ? 1 : digit) * 100;
                    digit = 0;
                }
                case '仟' -> {
                    section += (digit == 0 ? 1 : digit) * 1000;
                    digit = 0;
                }
                case '万' -> {
                    yuan = (yuan + section + digit) * 10000;
                    section = 0;
                    digit = 0;
                    sawUnit = true;
                }
                case '亿' -> {
                    yuan = (yuan + section + digit) * 100000000;
                    section = 0;
                    digit = 0;
                    sawUnit = true;
                }
                case '元' -> {
                    yuan += section + digit;
                    section = 0;
                    digit = 0;
                    sawUnit = true;
                }
                default -> sawUnit = sawUnit;
            }
        }
        if (!sawUnit) {
            // 无单位收尾（如 壹万贰仟 已在万分支累计；纯 拾佰仟 收尾时补计）
            yuan += section + digit;
        }
        return yuan == 0 ? null : yuan;
    }
}
