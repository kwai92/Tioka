package com.tioka.connector.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.sdk.ConnectorRetryPolicy;
import com.tioka.connector.api.sdk.ConnectorTypeHandler;
import com.tioka.framework.security.CredentialCipher;
import com.tioka.system.api.spi.EgressGuard;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 契约测试装配。 */
final class ConnectorSdkFixtures {

    private ConnectorSdkFixtures() {}

    static DefaultConnectorSdk sdk(EgressGuard egress) {
        return sdk(egress, new ConnectorRetryPolicy(3, 0L, 2.0), ms -> {});
    }

    static DefaultConnectorSdk sdk(EgressGuard egress,
                                   ConnectorRetryPolicy retry,
                                   DefaultConnectorSdk.Sleeper sleeper) {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryConnectorInstanceStore store = new InMemoryConnectorInstanceStore();
        List<ConnectorTypeHandler> handlers = List.of(new FakeConnectorTypeHandler(mapper));
        return new DefaultConnectorSdk(
                handlers,
                store,
                provider(new MemoryCredentialCipher()),
                provider(egress),
                mapper,
                retry,
                sleeper);
    }

    static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() throws BeansException {
                return value;
            }

            @Override
            public T getObject(Object... args) throws BeansException {
                return value;
            }

            @Override
            public T getIfAvailable() throws BeansException {
                return value;
            }

            @Override
            public T getIfUnique() throws BeansException {
                return value;
            }
        };
    }

    /** 测试用 CredentialCipher：密文含 AAD，防挪移。 */
    static final class MemoryCredentialCipher implements CredentialCipher {
        @Override
        public String encrypt(String plaintext, String credentialId, String ownerId) {
            String payload = credentialId + "\n" + ownerId + "\n" + plaintext;
            return "v1:" + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String ciphertext, String credentialId, String ownerId) {
            if (ciphertext == null || !ciphertext.startsWith("v1:")) {
                throw new IllegalArgumentException("密文格式非法");
            }
            String raw = new String(Base64.getDecoder().decode(ciphertext.substring(3)), StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", 3);
            if (parts.length != 3
                    || !parts[0].equals(credentialId)
                    || !parts[1].equals(ownerId)) {
                throw new IllegalArgumentException("AAD 不匹配");
            }
            return parts[2];
        }

        @Override
        public int currentKeyVersion() {
            return 1;
        }
    }

    /** 记录出网校验的 EgressGuard。 */
    static final class RecordingEgress implements EgressGuard {
        private final AtomicReference<String> lastUrl = new AtomicReference<>();
        private final boolean allowAll;

        RecordingEgress(boolean allowAll) {
            this.allowAll = allowAll;
        }

        @Override
        public void assertAllowed(String url) {
            lastUrl.set(url);
            if (!allowAll) {
                throw new IllegalArgumentException("egress denied: " + url);
            }
        }

        String lastUrl() {
            return lastUrl.get();
        }
    }
}
