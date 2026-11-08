package com.tioka.connector.infrastructure.feishu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 飞书事件订阅验签与 AES-256-CBC 解密（M2-03）。
 */
public final class FeishuCrypto {

    /** 时间戳与当前偏差超过此时长则拒绝（防重放）。 */
    public static final long MAX_SKEW_SECONDS = 300L;

    private FeishuCrypto() {
    }

    public static String sign(String encryptKey, String timestamp, String nonce, String rawBody) {
        return hexSha256(nullToEmpty(timestamp) + nullToEmpty(nonce) + nullToEmpty(encryptKey)
                + nullToEmpty(rawBody));
    }

    /**
     * 验签；失败抛 {@link IllegalArgumentException}。
     */
    public static void verify(String encryptKey, String timestamp, String nonce, String signature, String rawBody) {
        Objects.requireNonNull(encryptKey, "encryptKey");
        if (timestamp == null || timestamp.isBlank() || nonce == null || nonce.isBlank()
                || signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("缺少飞书签名头");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法时间戳", e);
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) {
            throw new IllegalArgumentException("时间戳过期");
        }
        String expected = sign(encryptKey, timestamp, nonce, rawBody);
        if (!constantTimeEquals(expected, signature.trim())) {
            throw new IllegalArgumentException("签名不匹配");
        }
    }

    /** 解密 {@code encrypt} 字段（base64(iv|ciphertext)）。 */
    public static String decrypt(String encryptKey, String encryptBase64) {
        Objects.requireNonNull(encryptKey, "encryptKey");
        if (encryptBase64 == null || encryptBase64.isBlank()) {
            throw new IllegalArgumentException("密文为空");
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] cipherBytes = Base64.getDecoder().decode(encryptBase64.trim());
            if (cipherBytes.length < 17) {
                throw new IllegalArgumentException("密文过短");
            }
            byte[] iv = Arrays.copyOfRange(cipherBytes, 0, 16);
            byte[] data = Arrays.copyOfRange(cipherBytes, 16, cipherBytes.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("解密失败: " + e.getMessage(), e);
        }
    }

    /** 验签并解密；{@code encrypt} 字段缺失时返回 rawBody（明文回调）。 */
    public static String verifyAndDecrypt(String encryptKey,
                                          String timestamp,
                                          String nonce,
                                          String signature,
                                          String rawBody,
                                          String encryptFieldBase64) {
        verify(encryptKey, timestamp, nonce, signature, rawBody);
        if (encryptFieldBase64 == null || encryptFieldBase64.isBlank()) {
            return rawBody == null ? "" : rawBody;
        }
        return decrypt(encryptKey, encryptFieldBase64);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private static String hexSha256(String input) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
