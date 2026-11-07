package com.tioka.system.application.apikey;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyMaterialTest {

    @Test
    void generateHasPrefixAndStableHash() {
        ApiKeyMaterial.Generated g = ApiKeyMaterial.generate();
        assertTrue(g.plaintext().startsWith("tk_live_"));
        assertEquals(8, g.keyPrefix().length());
        assertTrue(g.plaintext().startsWith("tk_live_" + g.keyPrefix()));
        assertEquals(64, g.keyHash().length());
        assertEquals(g.keyHash(), ApiKeyMaterial.sha256Hex(g.plaintext()));
    }

    @Test
    void encodeBase62NonEmpty() {
        String enc = ApiKeyMaterial.encodeBase62(new byte[]{0, 1, 2, (byte) 255});
        assertFalse(enc.isBlank());
        for (char c : enc.toCharArray()) {
            assertTrue(Character.isLetterOrDigit(c));
        }
    }
}
