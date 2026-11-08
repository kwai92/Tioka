package com.tioka.system.infrastructure.spi;

import com.tioka.framework.security.AesGcmCredentialCipher;
import com.tioka.framework.security.CredentialCipher;
import com.tioka.framework.security.LegacyAesEcb;
import com.tioka.system.infrastructure.SecretStoreRepository;
import com.tioka.system.infrastructure.persistence.SecretStoreDO;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSecretStoreTest {

    @Test
    void encryptRoundTripWithExplicitKey() {
        SecretStoreRepository repo = memRepo();
        CredentialCipher cipher = new AesGcmCredentialCipher("test-master-key1", 1);
        LocalSecretStore store = new LocalSecretStore(repo, cipher, "test-master-key1");
        String ref = store.put("ns", "k1", "super-secret");
        assertEquals("ns::k1", ref);
        assertEquals("super-secret", store.get("ns", "k1"));
        assertTrue(repo.find("ns", "k1").getCipherText().startsWith("v1:"));
        store.delete("ns", "k1");
        assertNull(store.get("ns", "k1"));
    }

    @Test
    void readsLegacyEcbCiphertext() {
        SecretStoreRepository repo = memRepo();
        SecretStoreDO row = new SecretStoreDO();
        row.setNs("ns");
        row.setSecretKey("legacy");
        row.setCipherText(LegacyAesEcb.encrypt("old-secret", "test-master-key1"));
        repo.upsert(row);

        CredentialCipher cipher = new AesGcmCredentialCipher("cred-master", 1);
        LocalSecretStore store = new LocalSecretStore(repo, cipher, "test-master-key1");
        assertEquals("old-secret", store.get("ns", "legacy"));
    }

    @Test
    void encryptRoundTripWithConfiguredKey() {
        MockEnvironment env = new MockEnvironment();
        CredentialCipher cipher = new AesGcmCredentialCipher("cfg-master-key16", 1);
        LocalSecretStore store = new LocalSecretStore(memRepo(), cipher, "cfg-master-key16", false, env);
        store.put("ns", "k2", "payload-xyz");
        assertEquals("payload-xyz", store.get("ns", "k2"));
    }

    @Test
    void prodWithoutKeyFailsFast() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        env.setActiveProfiles("prod");
        CredentialCipher cipher = new AesGcmCredentialCipher("any", 1);
        assertThrows(IllegalStateException.class,
                () -> new LocalSecretStore(memRepo(), cipher, "", true, env));
        assertThrows(IllegalStateException.class,
                () -> new LocalSecretStore(memRepo(), cipher, "  ", false, env));
    }

    @Test
    void localAllowsMissingKey() {
        Environment env = new MockEnvironment().withProperty("spring.profiles.active", "local");
        CredentialCipher cipher = new AesGcmCredentialCipher("", 1);
        LocalSecretStore store = new LocalSecretStore(memRepo(), cipher, "", false, env);
        store.put("ns", "k3", "dev-secret");
        assertEquals("dev-secret", store.get("ns", "k3"));
    }

    private static SecretStoreRepository memRepo() {
        Map<String, SecretStoreDO> mem = new ConcurrentHashMap<>();
        return new SecretStoreRepository() {
            private String k(String ns, String key) { return ns + "\0" + key; }

            @Override
            public void upsert(SecretStoreDO row) {
                mem.put(k(row.getNs(), row.getSecretKey()), row);
            }

            @Override
            public SecretStoreDO find(String ns, String key) {
                return mem.get(k(ns, key));
            }

            @Override
            public void softDelete(String ns, String key) {
                mem.remove(k(ns, key));
            }
        };
    }
}
