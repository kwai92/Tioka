package com.tioka.system.infrastructure.spi;

import com.tioka.framework.security.CredentialCipher;
import com.tioka.framework.security.LegacyAesEcb;
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

/**
 * SecretStore 实现：新写入走 {@link CredentialCipher}；读路径兼容 V145 前的 ECB 密文。
 */
@Component
public class LocalSecretStore implements SecretStore {
    private static final Logger log = LoggerFactory.getLogger(LocalSecretStore.class);

    private final SecretStoreRepository secrets;
    private final CredentialCipher cipher;
    private final String legacyMasterKey;

    @Autowired
    public LocalSecretStore(SecretStoreRepository secrets,
                            CredentialCipher cipher,
                            @Value("${tioka.secret.master-key:}") String legacyMasterKey,
                            @Value("${tioka.secret.require-external-key:false}") boolean requireExternalKey,
                            Environment environment) {
        this.secrets = secrets;
        this.cipher = cipher;
        String resolved = legacyMasterKey == null ? "" : legacyMasterKey.trim();
        boolean missing = resolved.isEmpty();
        boolean mustExternal = requireExternalKey
                || (environment != null && environment.acceptsProfiles(Profiles.of("prod")));
        if (missing && mustExternal) {
            throw new IllegalStateException(
                    "tioka.secret.master-key（或环境变量 TIOKA_SECRET_MASTER_KEY）在 prod / require-external-key 下必填");
        }
        if (missing) {
            resolved = LegacyAesEcb.DEV_DEFAULT_MASTER_KEY;
            log.warn("SecretStore 遗留 ECB 主密钥使用内置开发值；请通过 tioka.secret.master-key / TIOKA_SECRET_MASTER_KEY 配置");
        }
        this.legacyMasterKey = resolved;
    }

    /** 单测友好构造。 */
    LocalSecretStore(SecretStoreRepository secrets, CredentialCipher cipher, String legacyMasterKey) {
        this.secrets = secrets;
        this.cipher = cipher;
        this.legacyMasterKey = legacyMasterKey == null || legacyMasterKey.isBlank()
                ? LegacyAesEcb.DEV_DEFAULT_MASTER_KEY
                : legacyMasterKey.trim();
    }

    @Override
    public String put(String namespace, String key, String plaintext) {
        String id = namespace + "::" + key;
        SecretStoreDO row = new SecretStoreDO();
        row.setNs(namespace);
        row.setSecretKey(key);
        row.setCipherText(cipher.encrypt(plaintext == null ? "" : plaintext, key, namespace));
        secrets.upsert(row);
        return id;
    }

    @Override
    public String get(String namespace, String key) {
        SecretStoreDO row = secrets.find(namespace, key);
        if (row == null) {
            return null;
        }
        String ct = row.getCipherText();
        if (LegacyAesEcb.looksLikeLegacy(ct)) {
            return LegacyAesEcb.decrypt(ct, legacyMasterKey);
        }
        return cipher.decrypt(ct, key, namespace);
    }

    @Override
    public void delete(String namespace, String key) {
        secrets.softDelete(namespace, key);
    }
}
