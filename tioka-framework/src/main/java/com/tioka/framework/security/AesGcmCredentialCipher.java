package com.tioka.framework.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AES-256-GCM 凭证加密（FND-08 / ADR-042）。
 *
 * <p>密文：{@code v{keyVersion}:base64(iv[12] ‖ ciphertext ‖ tag[16])}；AAD = UTF-8({@code credentialId|ownerId})。
 */
public final class AesGcmCredentialCipher implements CredentialCipher {

    public static final String DEV_DEFAULT_MASTER_KEY = "tioka-dev-credential-master-v1";

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern WIRE = Pattern.compile("^v(\\d+):(.+)$");

    private final byte[] keyBytes;
    private final int keyVersion;
    private final boolean usingDevFallback;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCredentialCipher(String masterKey, int keyVersion, boolean requireExternalKey) {
        String resolved = masterKey == null ? "" : masterKey.trim();
        boolean missing = resolved.isEmpty();
        if (missing && requireExternalKey) {
            throw new IllegalStateException(
                    "tioka.credential.master-key（或 TIOKA_CREDENTIAL_MASTER_KEY）在 prod / require-external-key 下必填");
        }
        this.usingDevFallback = missing;
        if (missing) {
            resolved = DEV_DEFAULT_MASTER_KEY;
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("keyVersion 必须 ≥ 1");
        }
        this.keyVersion = keyVersion;
        this.keyBytes = deriveKey(resolved);
    }

    /** 单测 / 迁移友好：显式主密钥，不校验 requireExternal。 */
    public AesGcmCredentialCipher(String masterKey, int keyVersion) {
        this(masterKey, keyVersion, false);
    }

    public boolean usingDevFallback() {
        return usingDevFallback;
    }

    @Override
    public String encrypt(String plaintext, String credentialId, String ownerId) {
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(credentialId, ownerId));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return "v" + keyVersion + ":" + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("CredentialCipher encrypt failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext, String credentialId, String ownerId) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("ciphertext 为空");
        }
        Matcher m = WIRE.matcher(ciphertext.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("密文格式非法，期望 v{n}:base64(...)");
        }
        int version = Integer.parseInt(m.group(1));
        if (version != keyVersion) {
            // 本阶段仅支持当前版本；轮换多密钥在后续登记
            throw new IllegalArgumentException("未知密钥版本: " + version);
        }
        byte[] raw = Base64.getDecoder().decode(m.group(2));
        if (raw.length <= IV_LENGTH) {
            throw new IllegalArgumentException("密文过短");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
        byte[] ct = new byte[raw.length - IV_LENGTH];
        System.arraycopy(raw, IV_LENGTH, ct, 0, ct.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(credentialId, ownerId));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("密文损坏或 AAD 不匹配", e);
        }
    }

    @Override
    public int currentKeyVersion() {
        return keyVersion;
    }

    static byte[] aad(String credentialId, String ownerId) {
        String c = credentialId == null ? "" : credentialId;
        String o = ownerId == null ? "" : ownerId;
        return (c + "|" + o).getBytes(StandardCharsets.UTF_8);
    }

    /** SHA-256(master) → 32 字节 AES-256 密钥。 */
    static byte[] deriveKey(String masterKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
