package com.tioka.system.application.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuditRedactionTest {

    @Test
    void sensitiveFieldsAreMasked() {
        Map<String, Object> src = Map.of(
                "name", "alice",
                "password", "secret-pass",
                "token", "tka_xxx",
                "apiKey", "tk_live_xxx",
                "nested", Map.of("secret", "s3cr3t", "ok", 1)
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) AuditRedaction.redact(src);
        assertEquals("alice", out.get("name"));
        assertEquals("***", out.get("password"));
        assertEquals("***", out.get("token"));
        assertEquals("***", out.get("apiKey"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) out.get("nested");
        assertEquals("***", nested.get("secret"));
        assertEquals(1, nested.get("ok"));
    }

    @Test
    void includeWhitelistOnly() {
        Map<String, Object> src = Map.of("name", "a", "version", 2, "password", "x", "extra", "y");
        Map<String, Object> out = AuditRedaction.pickAndRedact(src, new String[]{"name", "version", "password"});
        assertEquals("a", out.get("name"));
        assertEquals(2, out.get("version"));
        assertEquals("***", out.get("password"));
        assertFalse(out.containsKey("extra"));
    }
}
