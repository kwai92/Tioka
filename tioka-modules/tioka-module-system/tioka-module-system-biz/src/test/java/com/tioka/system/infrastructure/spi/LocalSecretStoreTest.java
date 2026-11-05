package com.tioka.system.infrastructure.spi;

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

class LocalSecretStoreTest {

    @Test
    void encryptRoundTripWithExplicitKey() {
        LocalSecretStore store = new LocalSecretStore(memRepo(), "test-master-key1");
        String ref = store.put("ns", "k1", "super-secret");
        assertEquals("ns::k1", ref);
        assertEquals("super-secret", store.get("ns", "k1"));
        store.delete("ns", "k1");
        assertNull(store.get("ns", "k1"));
    }

    @Test
    void encryptRoundTripWithConfiguredKey() {
        MockEnvironment env = new MockEnvironment();
        LocalSecretStore store = new LocalSecretStore(memRepo(), "cfg-master-key16", false, env);
        store.put("ns", "k2", "payload-xyz");
        assertEquals("payload-xyz", store.get("ns", "k2"));
    }

    @Test
    void prodWithoutKeyFailsFast() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,
                () -> new LocalSecretStore(memRepo(), "", true, env));
        assertThrows(IllegalStateException.class,
                () -> new LocalSecretStore(memRepo(), "  ", false, env));
    }

    @Test
    void localAllowsMissingKey() {
        Environment env = new MockEnvironment().withProperty("spring.profiles.active", "local");
        LocalSecretStore store = new LocalSecretStore(memRepo(), "", false, env);
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
