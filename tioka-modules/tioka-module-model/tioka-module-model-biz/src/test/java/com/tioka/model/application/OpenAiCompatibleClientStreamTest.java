package com.tioka.model.application;

import com.tioka.model.api.LlmClientApi;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientStreamTest {

    @Test
    void mockStreamEmitsAtLeastTwoDeltas() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                noopSecretStore(), url -> {}, true);
        AtomicInteger deltas = new AtomicInteger();
        List<String> pieces = new ArrayList<>();
        LlmClientApi.CompletionResult result = client.streamComplete(
                "mock://openai", "k", "m",
                List.of(Map.of("role", "user", "content", "你好世界")),
                List.of(),
                d -> {
                    if (d.textDelta() != null && !d.textDelta().isEmpty()) {
                        deltas.incrementAndGet();
                        pieces.add(d.textDelta());
                    }
                });
        assertTrue(deltas.get() >= 2, "mock 流式至少应触发 ≥2 次 text delta，实际=" + deltas.get());
        assertTrue(result.content() != null && result.content().contains("你好世界"));
        assertTrue(String.join("", pieces).equals(result.content()));
    }

    private static SecretStore noopSecretStore() {
        return new SecretStore() {
            @Override
            public String put(String namespace, String key, String plaintext) {
                return namespace + "::" + key;
            }

            @Override
            public String get(String namespace, String key) {
                return null;
            }

            @Override
            public void delete(String namespace, String key) {
            }
        };
    }
}
