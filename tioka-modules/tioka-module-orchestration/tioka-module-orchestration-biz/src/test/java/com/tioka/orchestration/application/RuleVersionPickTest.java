package com.tioka.orchestration.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleVersionPickTest {

    @Test
    void stableHashing_sameSubjectAlwaysSameBucket() {
        String subject = "ticket-42";
        RuleVersionPicker.Pick a = RuleVersionPicker.pick("stable_1", "canary_1", 50, subject);
        RuleVersionPicker.Pick b = RuleVersionPicker.pick("stable_1", "canary_1", 50, subject);
        assertEquals(a.versionId(), b.versionId());
        assertEquals(a.canary(), b.canary());
    }

    @Test
    void zeroPercentAlwaysStable() {
        RuleVersionPicker.Pick p = RuleVersionPicker.pick("stable_1", "canary_1", 0, "any");
        assertEquals("stable_1", p.versionId());
        assertFalse(p.canary());
    }

    @Test
    void hundredPercentAlwaysCanary() {
        RuleVersionPicker.Pick p = RuleVersionPicker.pick("stable_1", "canary_1", 100, "any");
        assertEquals("canary_1", p.versionId());
        assertTrue(p.canary());
    }
}
