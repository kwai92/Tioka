package com.tioka.framework.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialCipherTest {

    @Test
    void roundTripAndAadPreventsSwap() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher("unit-master", 1);
        String wire = cipher.encrypt("sk-live", "prv_1", "model-provider");
        assertTrue(wire.startsWith("v1:"));
        assertEquals("sk-live", cipher.decrypt(wire, "prv_1", "model-provider"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(wire, "prv_2", "model-provider"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(wire, "prv_1", "other-ns"));
    }

    @Test
    void rejectUnknownVersionAndLegacyWire() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher("unit-master", 1);
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt("v9:AAAA", "id", "owner"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(LegacyAesEcb.encrypt("x", "k"), "id", "owner"));
    }

    @Test
    void currentKeyVersion() {
        assertEquals(3, new AesGcmCredentialCipher("m", 3).currentKeyVersion());
    }
}
