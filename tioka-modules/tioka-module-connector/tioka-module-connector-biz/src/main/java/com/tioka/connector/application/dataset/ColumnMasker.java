package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.ColumnSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列掩码（DAT-01 / M3-02 掩码表）：
 * PARTIAL 字符串保留前 3 后 4，中间 *；数字保留量级（12****）；
 * HASH sha256(tenantSalt + value) 前 16 位；NULLIFY 置 null；DROP 在列选择阶段剔除。
 */
@Component
public class ColumnMasker {

    private final String tenantSalt;

    public ColumnMasker(@Value("${tioka.connector.dataset.tenant-salt:tioka-default-salt}") String tenantSalt) {
        this.tenantSalt = tenantSalt == null ? "tioka-default-salt" : tenantSalt;
    }

    static String partial(Object value) {
        String s = String.valueOf(value);
        if (s.length() <= 7) {
            return "*".repeat(Math.max(1, s.length()));
        }
        return s.substring(0, 3) + "*".repeat(s.length() - 7) + s.substring(s.length() - 4);
    }

    /** 数字保留量级：首 2 位 + * 填充（12****）；字符串保留前 3 后 4。 */
    static String partialNumber(Object value) {
        String s = String.valueOf(value);
        if (s.length() <= 2) {
            return "*".repeat(Math.max(1, s.length()));
        }
        return s.substring(0, 2) + "*".repeat(s.length() - 2);
    }

    String hash(Object value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((tenantSalt + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 数字列用量级掩码，其余用前后保留。 */
    public static String partialFor(ColumnSpec spec, Object value) {
        if (spec != null && spec.type() != null
                && (spec.type().toLowerCase().contains("int")
                || spec.type().toLowerCase().contains("decimal")
                || spec.type().toLowerCase().contains("numeric"))) {
            return partialNumber(value);
        }
        return partial(value);
    }

    static Map<String, Object> copyOf(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }
}
