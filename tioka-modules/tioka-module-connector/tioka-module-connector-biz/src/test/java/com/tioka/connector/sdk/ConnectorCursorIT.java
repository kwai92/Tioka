package com.tioka.connector.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.sdk.ConnectorInstanceRegistration;
import com.tioka.connector.api.sdk.RetryableConnectorException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 游标契约：失败不得前移（M3-06 CON-SDK / {@code ConnectorCursorIT#failureKeepsCursor}）。
 */
class ConnectorCursorIT {

    @Test
    void failureKeepsCursor() {
        var egress = new ConnectorSdkFixtures.RecordingEgress(true);
        DefaultConnectorSdk sdk = ConnectorSdkFixtures.sdk(egress);
        ObjectMapper mapper = new ObjectMapper();
        String id = sdk.registerInstance(new ConnectorInstanceRegistration(
                "ws_demo",
                FakeConnectorTypeHandler.TYPE,
                "cursor-it",
                mapper.createObjectNode().put("baseUrl", "https://example.com"),
                100.0));
        sdk.writeCursor(id, "cursor-v1");

        assertThrows(RetryableConnectorException.class, () ->
                sdk.runSync(id, "cursor-v2", ctx -> {
                    throw new RetryableConnectorException("upstream 5xx");
                }));

        assertEquals("cursor-v1", sdk.readCursor(id));
        assertEquals(1, sdk.health(id).consecutiveFailures());
    }
}
