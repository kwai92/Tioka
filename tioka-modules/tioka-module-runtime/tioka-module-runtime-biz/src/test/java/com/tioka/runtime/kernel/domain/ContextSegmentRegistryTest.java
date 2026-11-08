package com.tioka.runtime.kernel.domain;

import com.tioka.runtime.api.context.ContextSegmentProvider;
import com.tioka.runtime.api.context.ContextZone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextSegmentRegistryTest {

    @Test
    void emptyPreservesBuilderPath() {
        ContextSegmentRegistry reg = new ContextSegmentRegistry(List.of());
        ContextBuilder builder = new ContextBuilder(new com.fasterxml.jackson.databind.ObjectMapper(), reg);
        assertTrue(builder.segments().isEmpty());
        assertTrue(builder.build(List.of()).isEmpty());
    }

    @Test
    void sortsByZoneThenOrder() {
        ContextSegmentProvider late = provider(ContextZone.A_STABLE_PREFIX, 20, "tools");
        ContextSegmentProvider early = provider(ContextZone.A_STABLE_PREFIX, 10, "platform");
        ContextSegmentProvider b = provider(ContextZone.B_THREAD_SNAPSHOT, 1, "memory");
        ContextSegmentRegistry reg = new ContextSegmentRegistry(List.of(late, b, early));
        assertEquals(List.of("platform", "tools", "memory"),
                reg.providers().stream().map(ContextSegmentProvider::segmentName).toList());
    }

    private static ContextSegmentProvider provider(ContextZone zone, int order, String name) {
        return new ContextSegmentProvider() {
            @Override public String segmentName() { return name; }
            @Override public ContextZone zone() { return zone; }
            @Override public int order() { return order; }
        };
    }
}
