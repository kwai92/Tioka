package com.tioka.model.infrastructure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderClientStreamTest {

    @Test
    void mockStreamEmitsPerCharacter() throws Exception {
        ProviderClient client = new ProviderClient(
                new com.tioka.system.api.spi.SecretStore() {
                    @Override public String put(String namespace, String key, String plaintext) { return namespace + "::" + key; }
                    @Override public String get(String namespace, String key) { return ""; }
                    @Override public void delete(String namespace, String key) {}
                },
                org.mockito.Mockito.mock(com.tioka.system.api.spi.EgressClient.class),
                true,
                32,
                new ObjectMapper());
        List<String> deltas = new ArrayList<>();
        ProviderClient.CompletionResult result = client.streamComplete(
                "mock://openai", "", "m",
                List.of(Map.of("role", "user", "content", "hi")),
                List.of(),
                d -> {
                    if (d != null && d.textDelta() != null) {
                        deltas.add(d.textDelta());
                    }
                });
        assertFalse(deltas.isEmpty());
        assertEquals(result.content(), String.join("", deltas));
        assertTrue(result.content().contains("hi"));
    }
}
