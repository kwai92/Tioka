package com.tioka.connector.infrastructure.feishu;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuCryptoTest {

    private static final String KEY = "unit_feishu_encrypt_key";

    @Test
    void verifyAndDecrypt() {
        String plain = "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"e1\"}}";
        String cipher = encryptLikeStub(KEY, plain);
        String wrapped = "{\"encrypt\":\"" + cipher + "\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String sig = FeishuCrypto.sign(KEY, ts, nonce, wrapped);

        String opened = FeishuCrypto.verifyAndDecrypt(KEY, ts, nonce, sig, wrapped, cipher);
        assertEquals(plain, opened);
    }

    @Test
    void tamperedRejected() {
        String plain = "{\"type\":\"url_verification\"}";
        String cipher = encryptLikeStub(KEY, plain);
        String wrapped = "{\"encrypt\":\"" + cipher + "\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce1234567890";
        String sig = FeishuCrypto.sign(KEY, ts, nonce, wrapped);

        assertThrows(IllegalArgumentException.class,
                () -> FeishuCrypto.verifyAndDecrypt(KEY, ts, nonce, sig, wrapped + "x", cipher));
    }

    @Test
    void staleTimestampRejected() {
        String plain = "{}";
        String cipher = encryptLikeStub(KEY, plain);
        String wrapped = "{\"encrypt\":\"" + cipher + "\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond() - FeishuCrypto.MAX_SKEW_SECONDS - 10);
        String nonce = "nonce_stale_00001";
        String sig = FeishuCrypto.sign(KEY, ts, nonce, wrapped);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FeishuCrypto.verify(KEY, ts, nonce, sig, wrapped));
        assertTrue(ex.getMessage().contains("时间戳"));
    }

    /** 与闸门 FeishuStubCrypto 相同算法，避免测试依赖 server 模块。 */
    private static String encryptLikeStub(String encryptKey, String plain) {
        try {
            byte[] key = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = java.util.Arrays.copyOfRange(
                    java.security.MessageDigest.getInstance("MD5")
                            .digest(encryptKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    0, 16);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return java.util.Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
