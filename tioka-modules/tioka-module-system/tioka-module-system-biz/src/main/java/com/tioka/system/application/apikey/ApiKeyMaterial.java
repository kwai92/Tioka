package com.tioka.system.application.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API Key 明文生成与哈希（FND-06）。
 * 格式：{@code tk_live_{8 位前缀}{32 字节 base62}}。
 */
public final class ApiKeyMaterial {
    public static final String PREFIX = "tk_live_";
    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyMaterial() {}

    public record Generated(String plaintext, String keyPrefix, String keyHash) {}

    public static Generated generate() {
        String keyPrefix = randomBase62(8);
        String secret = encodeBase62(randomBytes(32));
        String plaintext = PREFIX + keyPrefix + secret;
        return new Generated(plaintext, keyPrefix, sha256Hex(plaintext));
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean looksLikeApiKey(String token) {
        return token != null && token.startsWith(PREFIX);
    }

    private static byte[] randomBytes(int n) {
        byte[] buf = new byte[n];
        RANDOM.nextBytes(buf);
        return buf;
    }

    private static String randomBase62(int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = BASE62[RANDOM.nextInt(BASE62.length)];
        }
        return new String(out);
    }

    /** 将任意字节编码为 base62（无前导零）。 */
    static String encodeBase62(byte[] data) {
        // 大整数除法编码
        int[] digits = new int[data.length * 2]; // 足够空间
        int digitLen = 1;
        for (byte b : data) {
            int carry = b & 0xff;
            for (int i = 0; i < digitLen; i++) {
                int val = digits[i] * 256 + carry;
                digits[i] = val % 62;
                carry = val / 62;
            }
            while (carry > 0) {
                digits[digitLen++] = carry % 62;
                carry /= 62;
            }
        }
        char[] out = new char[digitLen];
        for (int i = 0; i < digitLen; i++) {
            out[digitLen - 1 - i] = BASE62[digits[i]];
        }
        return new String(out);
    }
}
