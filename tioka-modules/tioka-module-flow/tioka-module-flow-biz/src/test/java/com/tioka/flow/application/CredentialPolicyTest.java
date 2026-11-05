package com.tioka.flow.application;

import com.tioka.framework.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CredentialPolicyTest {
    @Test
    void rejectPlainApiKey() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(Map.of("id", "n1", "type", "HTTP", "config", Map.of("apiKey", "sk-xxx")))
        );
        BizException ex = assertThrows(BizException.class, () -> CredentialPolicy.assertNoWildKeys(graph));
        assertTrue(ex.getMessage().contains("apiKey") || ex.getMessage().contains("明文"));
    }

    @Test
    void allowApiKeyRef() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(Map.of("id", "n1", "type", "LLM",
                        "config", Map.of("apiKeyRef", "llm::openai")))
        );
        assertDoesNotThrow(() -> CredentialPolicy.assertNoWildKeys(graph));
        assertDoesNotThrow(() -> CredentialPolicy.assertSecretRef("apiKeyRef", "llm::openai"));
    }

    @Test
    void rejectNonRefShape() {
        assertThrows(BizException.class,
                () -> CredentialPolicy.assertSecretRef("apiKeyRef", "sk-not-a-ref"));
    }
}
