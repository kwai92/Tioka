package com.tioka.server.gate.support.feishu;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 飞书事件订阅签名 / AES-256-CBC 加解密（闸门桩侧，对齐 M2-03 规则）。
 * 生产侧实现归 CON-02；此处仅供 {@link FeishuStubServer} 推送加密事件。
 */
public final class FeishuStubCrypto {

    private FeishuStubCrypto() {
    }

    public static String sign(String encryptKey, String timestamp, String nonce, String rawBody) {
        return hexSha256(timestamp + nonce + encryptKey + (rawBody == null ? "" : rawBody));
    }

    /** 加密明文，返回 base64(iv|ciphertext)。 */
    public static String encrypt(String encryptKey, String plain) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] iv = Arrays.copyOfRange(MessageDigest.getInstance("MD5")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8)), 0, 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("飞书桩加密失败", e);
        }
    }

    public static String decrypt(String encryptKey, String encryptBase64) {
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
        } catch (Exception e) {
            throw new IllegalStateException("飞书桩解密失败: " + e.getMessage(), e);
        }
    }

    private static String hexSha256(String input) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
