package com.tioka.framework.foundation;

import com.foundation.common.id.Ids;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-02 BAS-26 第 1 步（core 子步骤 1a）：主键生成合流到 Foundation 公共域后的契约守护。
 *
 * <p>原 Tioka {@code com.tioka.framework.id.Ids} 已删除、不保留兼容层；本测试钉住
 * 00-施工总纲 §2.2 的 {@code {前缀}_{ULID}} 规则与单调性，防止后续回退到自建实现。
 */
class FoundationIdsContractTest {

    /** Crockford Base32：ULID 共 26 字符，排除 I / L / O / U。 */
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    @Test
    void resolvesToFoundationImplementation() {
        assertEquals("com.foundation.common.id.Ids", Ids.class.getName());
    }

    @Test
    void keepsPrefixedUlidShape() {
        String id = Ids.newId("turn");
        assertTrue(id.startsWith("turn_"), "应保留前缀与下划线：" + id);
        assertTrue(ULID.matcher(id.substring("turn_".length())).matches(), "应为 26 位 ULID：" + id);
    }

    @Test
    void generatesMonotonicUniqueIds() {
        String first = Ids.newId("item");
        String second = Ids.newId("item");
        assertNotEquals(first, second);
        assertTrue(first.compareTo(second) < 0, "同前缀连续生成应单调递增：" + first + " / " + second);
    }

    @Test
    void rejectsBlankOrNullPrefix() {
        assertThrows(IllegalArgumentException.class, () -> Ids.newId(" "));
        assertThrows(IllegalArgumentException.class, () -> Ids.newId(null));
    }
}
