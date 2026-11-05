package com.tioka.flow.application.exec;

import com.tioka.flow.application.CredentialPolicy;
import com.tioka.model.api.LlmClientApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/** 最小 Agent：LLM + system「agent」；可选 RuntimeSession 过重则不强制。 */
@Component
public class AgentNodeExecutor implements NodeExecutor {
    private final ObjectProvider<LlmClientApi> llmClient;

    public AgentNodeExecutor(ObjectProvider<LlmClientApi> llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String type() { return "Agent"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String model = String.valueOf(config.getOrDefault("model", "mock"));
        String baseUrl = String.valueOf(config.getOrDefault("baseUrl", "mock://openai"));
        String apiKeyRef = config.get("apiKeyRef") == null ? null : String.valueOf(config.get("apiKeyRef"));
        if (apiKeyRef != null && !apiKeyRef.isBlank()) {
            CredentialPolicy.assertSecretRef("apiKeyRef", apiKeyRef);
        }
        String user = extractText(input);
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "agent"),
                Map.of("role", "user", "content", user)
        );
        LlmClientApi client = llmClient.getIfAvailable();
        if (client == null) {
            return Map.of("content", "agent:" + user, "role", "agent");
        }
        LlmClientApi.CompletionResult result = client.complete(
                baseUrl, apiKeyRef == null ? "" : apiKeyRef, model, messages, List.of());
        return Map.of("content", result.content(), "role", "agent");
    }

    private static String extractText(Object input) {
        if (input == null) return "";
        if (input instanceof Map<?, ?> m) {
            Object t = m.containsKey("text") ? m.get("text") : m.get("content");
            return t == null ? String.valueOf(input) : String.valueOf(t);
        }
        return String.valueOf(input);
    }
}
