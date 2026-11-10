package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.ColumnSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列掩码四类离线测试（DAT-01 / M3-02 掩码表）：
 * PARTIAL（前 3 后 4 / 数字量级）、HASH（sha256(tenantSalt+value) 前 16 位）；
 * NULLIFY / DROP 属编译期与网关语义，在 DatasetGatewayIT 覆盖。
 */
class ColumnMaskerTest {

    private final ColumnMasker masker = new ColumnMasker("salt-ws-1");

    @Test
    void partialKeepsPrefix3AndSuffix4() {
        assertEquals("138****5678", ColumnMasker.partial("13812345678"));
        assertEquals("zha*****6789", ColumnMasker.partial("zhangsan6789"));
        // ≤7 位：全 *（长度保留）
        assertEquals("*******", ColumnMasker.partial("1234567"));
        assertEquals("*****", ColumnMasker.partial("abcde"));
        // 更短：至少一个 *
        assertEquals("*", ColumnMasker.partial(""));
    }

    @Test
    void partialNumberKeepsMagnitude() {
        assertEquals("12****", ColumnMasker.partialNumber(123456));
        assertEquals("99***", ColumnMasker.partialNumber(99887));
        // ≤2 位：全 *
        assertEquals("**", ColumnMasker.partialNumber(12));
        assertEquals("*", ColumnMasker.partialNumber(5));
    }

    @Test
    void partialForDispatchesByColumnType() {
        ColumnSpec numeric = new ColumnSpec("amount", "DECIMAL(10,2)", null, null);
        ColumnSpec text = new ColumnSpec("name", "varchar", null, null);
        assertEquals("12****", ColumnMasker.partialFor(numeric, 123456));
        assertEquals("138****5678", ColumnMasker.partialFor(text, "13812345678"));
        // int/numeric 关键词大小写不敏感
        assertEquals("12****", ColumnMasker.partialFor(
                new ColumnSpec("n", "bigint", null, null), 123456));
        // spec 为 null → 走字符串口径
        assertEquals("138****5678", ColumnMasker.partialFor(null, "13812345678"));
    }

    @Test
    void hashIsStableHex16AndSaltSensitive() {
        String h1 = masker.hash("4111111111111111");
        assertEquals(16, h1.length());
        assertTrue(h1.matches("[0-9a-f]{16}"));
        // 同 salt 稳定
        assertEquals(h1, masker.hash("4111111111111111"));
        // 不同 salt 不同值
        assertNotEquals(h1, new ColumnMasker("salt-ws-2").hash("4111111111111111"));
        // null salt → 缺省 salt
        assertNotEquals(h1, new ColumnMasker(null).hash("4111111111111111"));
    }

    @Test
    void copyOfIsIndependent() {
        Map<String, Object> row = Map.of("a", 1, "b", "x");
        Map<String, Object> copy = ColumnMasker.copyOf(row);
        copy.put("a", 2);
        assertEquals(1, row.get("a"));
        assertEquals(2, copy.get("a"));
    }
}
