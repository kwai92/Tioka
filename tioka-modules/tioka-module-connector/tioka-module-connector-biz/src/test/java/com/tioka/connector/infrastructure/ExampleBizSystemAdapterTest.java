package com.tioka.connector.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleBizSystemAdapterTest {

    @Test
    void echoInvoke() {
        ExampleBizSystemAdapter adapter = new ExampleBizSystemAdapter();
        assertEquals("example-erp", adapter.systemType());
        Map<String, Object> out = adapter.invoke("ping", Map.of("x", 1));
        assertEquals("ping", out.get("action"));
        assertTrue(Boolean.TRUE.equals(out.get("ok")));
    }
}
