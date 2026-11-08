package com.tioka.connector.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.sdk.ConnectorHealth;
import com.tioka.connector.api.sdk.ConnectorInstanceRegistration;
import com.tioka.connector.api.sdk.ConnectorRetryPolicy;
import com.tioka.connector.api.sdk.ConnectorSdk;
import com.tioka.connector.api.sdk.ConnectorSpec;
import com.tioka.connector.api.sdk.ConnectorSyncAction;
import com.tioka.connector.api.sdk.ConnectorSyncContext;
import com.tioka.connector.api.sdk.ConnectorTypeHandler;
import com.tioka.connector.api.sdk.RetryableConnectorException;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.CredentialCipher;
import com.tioka.system.api.spi.EgressGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CON-SDK 默认实现：规格注册、凭证引用、游标、限流、重试与健康。
 */
@Service
public class DefaultConnectorSdk implements ConnectorSdk {

    private final Map<String, ConnectorTypeHandler> handlers;
    private final ConnectorInstanceStore store;
    private final ObjectProvider<CredentialCipher> cipher;
    private final ObjectProvider<EgressGuard> egressGuard;
    private final ObjectMapper objectMapper;
    private final ConnectorRetryPolicy retryPolicy;
    private final ConcurrentHashMap<String, ConnectorTokenBucket> buckets = new ConcurrentHashMap<>();
    private final Sleeper sleeper;

    public DefaultConnectorSdk(List<ConnectorTypeHandler> handlers,
                               ConnectorInstanceStore store,
                               ObjectProvider<CredentialCipher> cipher,
                               ObjectProvider<EgressGuard> egressGuard,
                               ObjectMapper objectMapper) {
        this(handlers, store, cipher, egressGuard, objectMapper,
                ConnectorRetryPolicy.DEFAULT, Thread::sleep);
    }

    DefaultConnectorSdk(List<ConnectorTypeHandler> handlers,
                        ConnectorInstanceStore store,
                        ObjectProvider<CredentialCipher> cipher,
                        ObjectProvider<EgressGuard> egressGuard,
                        ObjectMapper objectMapper,
                        ConnectorRetryPolicy retryPolicy,
                        Sleeper sleeper) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(ConnectorTypeHandler::type, Function.identity(), (a, b) -> a));
        this.store = store;
        this.cipher = cipher;
        this.egressGuard = egressGuard;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
    }

    @Override
    public ConnectorSpec spec(String connectorType) {
        ConnectorTypeHandler handler = handlers.get(connectorType);
        if (handler == null) {
            throw new IllegalArgumentException("未知连接器类型: " + connectorType);
        }
        return handler.spec();
    }

    @Override
    public ConnectorHealth health(String connectorInstanceId) {
        return require(connectorInstanceId).toHealth();
    }

    @Override
    public String readCursor(String connectorInstanceId) {
        return require(connectorInstanceId).syncCursor();
    }

    @Override
    public void writeCursor(String connectorInstanceId, String cursor) {
        ConnectorInstanceState state = require(connectorInstanceId);
        state.setSyncCursor(cursor);
        store.save(state);
    }

    @Override
    public String registerInstance(ConnectorInstanceRegistration registration) {
        spec(registration.connectorType());
        JsonNode config = registration.config() == null
                ? objectMapper.createObjectNode()
                : registration.config();
        String id = Ids.newId("cci");
        ConnectorInstanceState state = new ConnectorInstanceState(
                id,
                registration.workspaceId(),
                registration.connectorType(),
                registration.name(),
                config,
                registration.rateLimitQps());
        store.save(state);
        buckets.put(id, new ConnectorTokenBucket(registration.rateLimitQps()));
        return id;
    }

    @Override
    public void bindCredential(String connectorInstanceId, String credentialId, String plaintext) {
        if (credentialId == null || credentialId.isBlank()) {
            throw new IllegalArgumentException("credentialId 必填");
        }
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext 不能为 null");
        }
        CredentialCipher c = requireCipher();
        ConnectorInstanceState state = require(connectorInstanceId);
        String ciphertext = c.encrypt(plaintext, credentialId, connectorInstanceId);
        state.setCredentialRef(ciphertext, credentialId);
        store.save(state);
    }

    @Override
    public Optional<String> resolveCredential(String connectorInstanceId) {
        ConnectorInstanceState state = require(connectorInstanceId);
        if (state.credentialRef() == null || state.credentialId() == null) {
            return Optional.empty();
        }
        return Optional.of(requireCipher().decrypt(state.credentialRef(), state.credentialId(), connectorInstanceId));
    }

    @Override
    public boolean tryAcquire(String connectorInstanceId) {
        ConnectorInstanceState state = require(connectorInstanceId);
        ConnectorTokenBucket bucket = buckets.computeIfAbsent(
                connectorInstanceId, id -> new ConnectorTokenBucket(state.rateLimitQps()));
        return bucket.tryAcquire();
    }

    @Override
    public <T> T runSync(String connectorInstanceId,
                         String nextCursorOnSuccess,
                         ConnectorSyncAction<T> action) {
        ConnectorInstanceState state = require(connectorInstanceId);
        if (!tryAcquire(connectorInstanceId)) {
            throw new RetryableConnectorException("RATE_LIMITED:" + connectorInstanceId);
        }
        ConnectorSyncContext ctx = new DefaultSyncContext(state);
        int attempts = retryPolicy.maxAttempts();
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                T result = action.execute(ctx);
                if (nextCursorOnSuccess != null) {
                    state.setSyncCursor(nextCursorOnSuccess);
                }
                state.recordSuccess(Instant.now());
                store.save(state);
                return result;
            } catch (Exception ex) {
                last = ex;
                if (!isRetryable(ex) || i == attempts - 1) {
                    break;
                }
                sleepQuietly(retryPolicy.backoffMs(i));
            }
        }
        // 失败：不写游标，只记健康
        String msg = last == null ? "unknown" : last.getMessage();
        state.recordFailure(msg, Instant.now());
        store.save(state);
        if (last instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(last);
    }

    @Override
    public void assertEgressAllowed(String url) {
        EgressGuard guard = egressGuard.getIfAvailable();
        if (guard == null) {
            throw new IllegalStateException("EgressGuard 未装配");
        }
        guard.assertAllowed(url);
    }

    private CredentialCipher requireCipher() {
        CredentialCipher c = cipher.getIfAvailable();
        if (c == null) {
            throw new IllegalStateException("CredentialCipher 未装配（依赖 FND-08）");
        }
        return c;
    }

    private ConnectorInstanceState require(String instanceId) {
        return store.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("未知连接器实例: " + instanceId));
    }

    private static boolean isRetryable(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof RetryableConnectorException || cur instanceof IOException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("同步退避被中断", e);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final class DefaultSyncContext implements ConnectorSyncContext {
        private final ConnectorInstanceState state;

        private DefaultSyncContext(ConnectorInstanceState state) {
            this.state = state;
        }

        @Override
        public String instanceId() {
            return state.id();
        }

        @Override
        public String workspaceId() {
            return state.workspaceId();
        }

        @Override
        public String currentCursor() {
            return state.syncCursor();
        }

        @Override
        public Optional<String> resolveCredential() {
            return DefaultConnectorSdk.this.resolveCredential(state.id());
        }

        @Override
        public void assertEgressAllowed(String url) {
            DefaultConnectorSdk.this.assertEgressAllowed(url);
        }
    }
}
