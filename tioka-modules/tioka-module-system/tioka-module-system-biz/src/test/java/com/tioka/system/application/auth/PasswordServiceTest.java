package com.tioka.system.application.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordServiceTest {
    private final PasswordService passwords = new PasswordService();

    @Test
    void bcryptRoundTrip() {
        String hash = passwords.hash("Secret!23456");
        assertTrue(PasswordService.isBcrypt(hash));
        assertTrue(hash.startsWith("$2a$12$") || hash.startsWith("$2b$12$"));
        assertTrue(passwords.matches("Secret!23456", hash));
        assertFalse(passwords.matches("wrong", hash));
    }

    @Test
    void legacySha256UpgradedOnLogin() {
        String legacy = PasswordService.sha256("legacy-pass");
        assertTrue(passwords.matches("legacy-pass", legacy));
        assertTrue(passwords.needsUpgrade(legacy));
        String upgraded = passwords.hash("legacy-pass");
        assertTrue(PasswordService.isBcrypt(upgraded));
        assertTrue(upgraded.startsWith("$2a$12$") || upgraded.startsWith("$2b$12$"));
    }
}
