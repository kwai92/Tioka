package com.tioka.server.security;

import com.tioka.framework.security.CredentialCipher;
import com.tioka.framework.security.LegacyAesEcb;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.api.spi.SecretStore;
import com.tioka.system.infrastructure.SecretStoreRepository;
import com.tioka.system.infrastructure.persistence.SecretStoreDO;
import db.migration.V145__CredentialReencrypt;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FND-08 / PK-14a：遗留 ECB 密文经 V145 重加密后明文不变、格式为 GCM。
 */
@SpringBootTest
@Tag("it")
class CredentialReencryptIT {

    private static final String LEGACY_KEY = "it-legacy-master16";
    private static final String CRED_KEY = "it-credential-master-key";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.secret.master-key", () -> LEGACY_KEY);
        registry.add("tioka.credential.master-key", () -> CRED_KEY);
        registry.add("tioka.credential.key-version", () -> "1");
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretStore secrets;
    @Autowired SecretStoreRepository secretRepo;
    @Autowired CredentialCipher cipher;

    @Test
    void legacyRowsReencryptToGcmAndRemainReadable() throws Exception {
        String ns = "model-provider";
        String key = "prv_reenc_it";
        String plain = "sk-reencrypt-plain";

        // 模拟迁移前的 ECB 行（绕过 LocalSecretStore 的 GCM 写入）
        SecretStoreDO row = new SecretStoreDO();
        row.setNs(ns);
        row.setSecretKey(key);
        row.setCipherText(LegacyAesEcb.encrypt(plain, LEGACY_KEY));
        secretRepo.upsert(row);
        assertTrue(LegacyAesEcb.looksLikeLegacy(secretRepo.find(ns, key).getCipherText()));

        System.setProperty("tioka.secret.master-key", LEGACY_KEY);
        System.setProperty("tioka.credential.master-key", CRED_KEY);
        System.setProperty("tioka.credential.key-version", "1");
        try (Connection conn = dataSource.getConnection()) {
            new V145__CredentialReencrypt().migrate(new Context() {
                @Override
                public Connection getConnection() {
                    return conn;
                }

                @Override
                public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
                    return null;
                }
            });
        } finally {
            System.clearProperty("tioka.secret.master-key");
            System.clearProperty("tioka.credential.master-key");
            System.clearProperty("tioka.credential.key-version");
        }

        String wire = secretRepo.find(ns, key).getCipherText();
        assertTrue(wire.startsWith("v1:"), wire);
        assertEquals(plain, cipher.decrypt(wire, key, ns));
        assertEquals(plain, secrets.get(ns, key));

        Integer gcmCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='secret_store' AND column_name='cipher_text_gcm'",
                Integer.class);
        assertEquals(0, gcmCol);
    }
}
