package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.spi.SecretStore;
import com.tioka.system.infrastructure.SecretStoreRepository;
import com.tioka.system.infrastructure.persistence.SecretStoreDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class LocalSecretStore implements SecretStore {
    private static final Logger log = LoggerFactory.getLogger(LocalSecretStore.class);
    /** local 缺省开发密钥（16 字节 AES）；生产禁止依赖此值 */
    static final String DEV_DEFAULT_MASTER_KEY = "tioka-dev-sec-16";

    private final SecretStoreRepository secrets;
    private final byte[] keyBytes;

    @Autowired
    public LocalSecretStore(SecretStoreRepository secrets,
                            @Value("${tioka.secret.master-key:}") String masterKey,
                            @Value("${tioka.secret.require-external-key:false}") boolean requireExternalKey,
                            Environment environment) {
        this.secrets = secrets;
        String resolved = masterKey == null ? "" : masterKey.trim();
        boolean missing = resolved.isEmpty();
        boolean mustExternal = requireExternalKey
                || (environment != null && environment.acceptsProfiles(Profiles.of("prod")));
        if (missing && mustExternal) {
            throw new IllegalStateException(
                    "tioka.secret.master-key（或环境变量 TIOKA_SECRET_MASTER_KEY）在 prod / require-external-key 下必填");
        }
        if (missing) {
            resolved = DEV_DEFAULT_MASTER_KEY;
            log.warn("SecretStore 使用内置开发 master key；请通过 tioka.secret.master-key / TIOKA_SECRET_MASTER_KEY 配置外部密钥");
        }
        this.keyBytes = normalizeAesKey(resolved);
    }

    /** 单测友好构造：显式 master key，不校验 profile */
    LocalSecretStore(SecretStoreRepository secrets, String masterKey) {
        this.secrets = secrets;
        String resolved = masterKey == null || masterKey.isBlank() ? DEV_DEFAULT_MASTER_KEY : masterKey.trim();
        this.keyBytes = normalizeAesKey(resolved);
    }

    @Override
    public String put(String namespace, String key, String plaintext) {
        String id = namespace + "::" + key;
        SecretStoreDO row = new SecretStoreDO();
        row.setNs(namespace);
        row.setSecretKey(key);
        row.setCipherText(encrypt(plaintext));
        secrets.upsert(row);
        return id;
    }

    @Override
    public String get(String namespace, String key) {
        SecretStoreDO row = secrets.find(namespace, key);
        return row == null ? null : decrypt(row.getCipherText());
    }

    @Override
    public void delete(String namespace, String key) {
        secrets.softDelete(namespace, key);
    }

    private String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    private String decrypt(String ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    static byte[] normalizeAesKey(String masterKey) {
        byte[] raw = masterKey.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16];
        System.arraycopy(raw, 0, key, 0, Math.min(16, raw.length));
        return key;
    }
}
