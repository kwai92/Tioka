package com.tioka.governance.application.label;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GOV-10 内置正则 PII 脱敏（ADR-029 第 49 条提前到 M3；M3-02 DAT-02）。
 *
 * <p>M3-02/ADR-040 未冻结具体正则表，本实现内置常见四类（偏差登记于批次文档）：
 * 身份证（18 位含校验位）、手机号（1[3-9] 段 11 位）、银行卡（13〜19 位 + Luhn 校验）、邮箱。
 * 供 {@code DefaultSinkGuard} REDACT 路径与 M4 护栏管线 {@code pii.detect} 检查点复用。
 */
public final class RegexPiiHook {

    /** 身份证 18 位（前 17 数字 + 校验位 X/x/digit）。 */
    public static final Pattern ID_CARD =
            Pattern.compile("(?<![0-9Xx])[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9Xx])");
    /** 手机号（不含前导数字）。 */
    public static final Pattern MOBILE =
            Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");
    /** 银行卡候选（13〜19 位数字，Luhn 通过才算命中）。 */
    public static final Pattern BANK_CARD_CANDIDATE =
            Pattern.compile("(?<![0-9-])\\d{13,19}(?![0-9])");
    /** 邮箱。 */
    public static final Pattern EMAIL =
            Pattern.compile("(?<![\\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![\\w.-])");

    private static final String MASK = "[已脱敏]";

    private RegexPiiHook() {}

    /** 单类命中检测（供测试与审计计数）。 */
    public static boolean matches(String category, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Matcher m = patternOf(category).matcher(text);
        while (m.find()) {
            if (!"bank_card".equals(category) || luhnValid(m.group())) {
                return true;
            }
        }
        return false;
    }

    /** 全类脱敏：命中片段替换为 {@code [已脱敏]}。 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replaceAll(text, ID_CARD);
        out = replaceAll(out, MOBILE);
        out = replaceAll(out, EMAIL);
        out = replaceLuhn(out);
        return out;
    }

    /** 脱敏命中计数（类别 → 次数），供审计与「脱敏后无命中」判定。 */
    public static Map<String, Integer> hitCounts(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        count(out, "id_card", text, ID_CARD, false);
        count(out, "mobile", text, MOBILE, false);
        count(out, "email", text, EMAIL, false);
        count(out, "bank_card", text, BANK_CARD_CANDIDATE, true);
        return out;
    }

    public static boolean luhnValid(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) {
                return false;
            }
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static void count(Map<String, Integer> out, String name, String text,
                              Pattern p, boolean luhn) {
        Matcher m = p.matcher(text);
        List<Integer> spans = new ArrayList<>();
        while (m.find()) {
            if (!luhn || luhnValid(m.group())) {
                spans.add(m.start());
                spans.add(m.end());
                out.merge(name, 1, Integer::sum);
            }
        }
    }

    private static String replaceAll(String text, Pattern p) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(MASK));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceLuhn(String text) {
        Matcher m = BANK_CARD_CANDIDATE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (luhnValid(m.group())) {
                m.appendReplacement(sb, Matcher.quoteReplacement(MASK));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Pattern patternOf(String category) {
        return switch (category) {
            case "id_card" -> ID_CARD;
            case "mobile" -> MOBILE;
            case "email" -> EMAIL;
            case "bank_card" -> BANK_CARD_CANDIDATE;
            default -> throw new IllegalArgumentException("未知 PII 类别: " + category);
        };
    }
}
