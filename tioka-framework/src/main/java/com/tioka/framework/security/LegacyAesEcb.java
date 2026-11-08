package com.tioka.framework.security;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 遗留 {@code LocalSecretStore} AES/ECB 密文（无版本前缀），仅供 V145 重加密与读路径双轨使用。
 */
public final class LegacyAesEcb {

    /** 与历史 LocalSecretStore 一致的开发缺省主密钥。 */
    public static final String DEV_DEFAULT_MASTER_KEY = "tioka-dev-sec-16";

    private LegacyAesEcb() {
    }

    private static final java.util.regex.Pattern GCM_WIRE =
            java.util.regex.Pattern.compile("^v\\d+:.+$");

    public static boolean looksLikeLegacy(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return false;
        }
        return !GCM_WIRE.matcher(ciphertext.trim()).matches();
    }

    public static String encrypt(String plaintext, String masterKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(normalizeKey(masterKey), "AES"));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("legacy ECB encrypt failed", e);
        }
    }

    public static String decrypt(String ciphertext, String masterKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(normalizeKey(masterKey), "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("legacy ECB decrypt failed", e);
        }
    }

    /** UTF-8 截断/零填到 16 字节（历史 AES-128）。 */
    public static byte[] normalizeKey(String masterKey) {
        String resolved = masterKey == null || masterKey.isBlank() ? DEV_DEFAULT_MASTER_KEY : masterKey.trim();
        byte[] raw = resolved.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16];
        System.arraycopy(raw, 0, key, 0, Math.min(16, raw.length));
        return key;
    }
}
