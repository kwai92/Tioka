package com.tioka.runtime.kernel.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventIdTest {

    @Test
    void compareRules() {
        // 41.0 < 41.3 < 41 < 42.0
        assertTrue(EventId.of(41, 0).compareTo(EventId.of(41, 3)) < 0);
        assertTrue(EventId.of(41, 3).compareTo(EventId.of(41)) < 0);
        assertTrue(EventId.of(41).compareTo(EventId.of(42, 0)) < 0);
        assertEquals(0, EventId.parse("41").compareTo(EventId.of(41)));
        assertEquals("41.3", EventId.parse("41.3").toString());
    }
}
