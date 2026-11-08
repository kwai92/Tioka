package com.tioka.connector.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.sdk.ConnectorHealthStatus;
import com.tioka.connector.api.sdk.ConnectorInstanceRegistration;
import com.tioka.connector.api.sdk.ConnectorRetryPolicy;
import com.tioka.connector.api.sdk.ConnectorSpec;
import com.tioka.connector.api.sdk.RetryableConnectorException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CON-SDK 契约：规格、凭证、游标、限流、健康（M3-06 §2 / P3-A10）。
 */
class ConnectorSdkContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConnectorSdkFixtures.RecordingEgress egress = new ConnectorSdkFixtures.RecordingEgress(true);
    private final DefaultConnectorSdk sdk = ConnectorSdkFixtures.sdk(egress);

    @Test
    void specExposesJsonSchemaAndCapabilities() {
        ConnectorSpec spec = sdk.spec(FakeConnectorTypeHandler.TYPE);
        assertEquals(FakeConnectorTypeHandler.TYPE, spec.type());
        assertTrue(spec.listChanges());
        assertTrue(spec.fetchDocuments());
        assertFalse(spec.queryDataset());
        assertFalse(spec.sourceAcl());
        JsonNode schema = spec.configSchema();
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("properties").has("baseUrl"));
        assertTrue(schema.path("required").isArray());
    }

    @Test
    void credentialStoresCipherRefOnlyAndResolvesViaCipher() {
        String id = register(1.0);
        sdk.bindCredential(id, "cred-1", "super-secret");
        assertEquals("super-secret", sdk.resolveCredential(id).orElseThrow());

        ConnectorSdkFixtures.MemoryCredentialCipher cipher = new ConnectorSdkFixtures.MemoryCredentialCipher();
        String cipherText = cipher.encrypt("token", "cred-1", id);
        assertTrue(cipherText.startsWith("v1:"));
        assertFalse(cipherText.contains("token"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(cipherText, "cred-1", "other-instance"));
        assertEquals("token", cipher.decrypt(cipherText, "cred-1", id));
    }

    @Test
    void rateLimitRejectsWhenBucketEmpty() {
        String id = register(1.0);
        assertTrue(sdk.tryAcquire(id));
        assertFalse(sdk.tryAcquire(id));
    }

    @Test
    void healthDegradesAfterThreeConsecutiveFailures() {
        String id = register(100.0);
        for (int i = 0; i < ConnectorHealthStatus.DEGRADED_FAILURE_THRESHOLD; i++) {
            int n = i;
            assertThrows(IllegalStateException.class, () ->
                    sdk.runSync(id, "cursor-" + n, ctx -> {
                        throw new IllegalStateException("boom-" + n);
                    }));
        }
        var health = sdk.health(id);
        assertEquals(ConnectorHealthStatus.DEGRADED, health.status());
        assertEquals(3, health.consecutiveFailures());
        assertTrue(health.lastError().contains("boom"));
        assertNull(sdk.readCursor(id));

        sdk.runSync(id, "ok", ctx -> "done");
        health = sdk.health(id);
        assertEquals(ConnectorHealthStatus.ACTIVE, health.status());
        assertEquals(0, health.consecutiveFailures());
        assertEquals("ok", sdk.readCursor(id));
    }

    @Test
    void retryableErrorsBackoffThenSucceedWithoutPrematureCursorAdvance() {
        AtomicInteger calls = new AtomicInteger();
        DefaultConnectorSdk local = ConnectorSdkFixtures.sdk(
                egress, new ConnectorRetryPolicy(3, 0L, 2.0), ms -> {});
        String id = local.registerInstance(new ConnectorInstanceRegistration(
                "ws_demo", FakeConnectorTypeHandler.TYPE, "fake-1",
                mapper.createObjectNode().put("baseUrl", "https://example.com"), 100.0));
        local.writeCursor(id, "c0");

        String result = local.runSync(id, "c1", ctx -> {
            if (calls.incrementAndGet() < 3) {
                throw new RetryableConnectorException("transient");
            }
            assertEquals("c0", ctx.currentCursor());
            ctx.assertEgressAllowed("https://example.com/sync");
            return "payload";
        });

        assertEquals("payload", result);
        assertEquals(3, calls.get());
        assertEquals("c1", local.readCursor(id));
        assertEquals("https://example.com/sync", egress.lastUrl());
        assertEquals(ConnectorHealthStatus.ACTIVE, local.health(id).status());
    }

    private String register(double qps) {
        return sdk.registerInstance(new ConnectorInstanceRegistration(
                "ws_demo",
                FakeConnectorTypeHandler.TYPE,
                "fake-" + qps,
                mapper.createObjectNode().put("baseUrl", "https://example.com"),
                qps));
    }
}
