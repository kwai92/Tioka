package com.tioka.governance.application.label;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOV-10 内置正则 PII 脱敏离线测试（DAT-02）。四类各 5 正例 / 5 反例 + 脱敏与计数。
 * 全部纯函数，无外部依赖。
 */
class RegexPiiHookTest {

    // ---------- 身份证（18 位） ----------
    @Test
    void idCardPositives() {
        assertTrue(RegexPiiHook.matches("id_card", "110101199003077518"));
        assertTrue(RegexPiiHook.matches("id_card", "440524192001010014"));
        assertTrue(RegexPiiHook.matches("id_card", "320102199001011230"));
        assertTrue(RegexPiiHook.matches("id_card", "11010519491231002X")); // 校验位大写 X
        assertTrue(RegexPiiHook.matches("id_card", "客户证件号11010519491231002x请核验")); // 小写 x + 内嵌
    }

    @Test
    void idCardNegatives() {
        assertFalse(RegexPiiHook.matches("id_card", "110101990030775"));      // 15 位旧式
        assertFalse(RegexPiiHook.matches("id_card", "11010119900307751"));    // 17 位缺校验位
        assertFalse(RegexPiiHook.matches("id_card", "110101199013077518"));   // 13 月非法
        assertFalse(RegexPiiHook.matches("id_card", "110101199003327518"));   // 32 日非法
        assertFalse(RegexPiiHook.matches("id_card", "110101199003007518"));   // 00 日非法
    }

    // ---------- 手机号 ----------
    @Test
    void mobilePositives() {
        assertTrue(RegexPiiHook.matches("mobile", "13812345678"));
        assertTrue(RegexPiiHook.matches("mobile", "19912345678"));
        assertTrue(RegexPiiHook.matches("mobile", "15912345678"));
        assertTrue(RegexPiiHook.matches("mobile", "联系13755556666谢谢"));
        assertTrue(RegexPiiHook.matches("mobile", "回拨 18600001234 确认"));
    }

    @Test
    void mobileNegatives() {
        assertFalse(RegexPiiHook.matches("mobile", "12812345678"));    // 12 段
        assertFalse(RegexPiiHook.matches("mobile", "1381234567"));     // 10 位
        assertFalse(RegexPiiHook.matches("mobile", "138123456789"));   // 12 位
        assertFalse(RegexPiiHook.matches("mobile", "23812345678"));    // 非 1 开头
        assertFalse(RegexPiiHook.matches("mobile", "8613812345678"));  // 与前缀数字连写不命中
    }

    // ---------- 银行卡（13〜19 位 + Luhn） ----------
    @Test
    void bankCardPositives() {
        assertTrue(RegexPiiHook.matches("bank_card", "4111111111111111"));
        assertTrue(RegexPiiHook.matches("bank_card", "5555555555554444"));
        assertTrue(RegexPiiHook.matches("bank_card", "378282246310005"));        // 15 位
        assertTrue(RegexPiiHook.matches("bank_card", "卡号6011111111111117请保存"));
        assertTrue(RegexPiiHook.matches("bank_card", "4242424242424242"));
    }

    @Test
    void bankCardNegatives() {
        assertFalse(RegexPiiHook.matches("bank_card", "4111111111111112"));      // Luhn 失败
        assertFalse(RegexPiiHook.matches("bank_card", "1234567890123"));         // 13 位但 Luhn 失败
        assertFalse(RegexPiiHook.matches("bank_card", "411111111111"));          // 12 位不足
        assertFalse(RegexPiiHook.matches("bank_card", "41111111111111111111"));  // 20 位连写
        assertFalse(RegexPiiHook.matches("bank_card", "4111-1111-1111-1111"));   // 连字符分段
    }

    // ---------- 邮箱 ----------
    @Test
    void emailPositives() {
        assertTrue(RegexPiiHook.matches("email", "user@example.com"));
        assertTrue(RegexPiiHook.matches("email", "alice.smith+work@sub.example.co"));
        assertTrue(RegexPiiHook.matches("email", "A_B%test@my-domain.cn"));
        assertTrue(RegexPiiHook.matches("email", "联系foo_bar@163.com获取详情"));
        assertTrue(RegexPiiHook.matches("email", "x@y.io"));
    }

    @Test
    void emailNegatives() {
        assertFalse(RegexPiiHook.matches("email", "user@example"));       // 无 TLD
        assertFalse(RegexPiiHook.matches("email", "@example.com"));       // 缺本地部分
        assertFalse(RegexPiiHook.matches("email", "user@@example.com"));  // 双 @
        assertFalse(RegexPiiHook.matches("email", "user@exam_ple.com"));  // 域名下划线
        assertFalse(RegexPiiHook.matches("email", "user@example.c"));     // TLD 一位
    }

    // ---------- redact 与 hitCounts ----------
    @Test
    void redactReplacesAllCategories() {
        String text = "张三 13812345678 id=110101199003077518 "
                + "mail zhang@example.com 卡 4111111111111111";
        String out = RegexPiiHook.redact(text);
        assertFalse(out.contains("13812345678"));
        assertFalse(out.contains("110101199003077518"));
        assertFalse(out.contains("zhang@example.com"));
        assertFalse(out.contains("4111111111111111"));
        assertEquals(4, out.split("\\[已脱敏]", -1).length - 1);
    }

    @Test
    void redactKeepsCleanText() {
        String text = "今天天气不错，适合写代码。订单号 ORD-2026-0001。";
        assertEquals(text, RegexPiiHook.redact(text));
        assertTrue(RegexPiiHook.hitCounts(text).isEmpty());
    }

    @Test
    void hitCountsByCategory() {
        String text = "13812345678 和 15999998888，邮箱 a@b.com，"
                + "卡 4111111111111111，坏卡 4111111111111112。";
        Map<String, Integer> counts = RegexPiiHook.hitCounts(text);
        assertEquals(2, counts.get("mobile"));
        assertEquals(1, counts.get("email"));
        assertEquals(1, counts.get("bank_card")); // Luhn 失败的不计
        assertFalse(counts.containsKey("id_card"));
    }

    @Test
    void luhnBasics() {
        assertTrue(RegexPiiHook.luhnValid("4111111111111111"));
        assertFalse(RegexPiiHook.luhnValid("4111111111111112"));
        assertFalse(RegexPiiHook.luhnValid("12345"));           // 长度不足
        assertFalse(RegexPiiHook.luhnValid("411111111111111111111")); // 超长
        assertFalse(RegexPiiHook.luhnValid(null));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertFalse(RegexPiiHook.matches("mobile", null));
        assertFalse(RegexPiiHook.matches("mobile", ""));
        assertNull(RegexPiiHook.redact(null));
        assertEquals("", RegexPiiHook.redact(""));
        assertTrue(RegexPiiHook.hitCounts(null).isEmpty());
    }
}
