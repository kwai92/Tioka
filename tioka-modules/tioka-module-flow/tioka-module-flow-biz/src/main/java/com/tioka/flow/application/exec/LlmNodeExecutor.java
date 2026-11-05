package com.tioka.flow.application.exec;

import com.tioka.flow.application.CredentialPolicy;
import com.tioka.model.api.LlmClientApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class LlmNodeExecutor implements NodeExecutor {
    private final ObjectProvider<LlmClientApi> llmClient;

    public LlmNodeExecutor(ObjectProvider<LlmClientApi> llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String type() { return "LLM"; }

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

        String prompt = extractPrompt(input);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));

        LlmClientApi client = llmClient.getIfAvailable();
        if (client == null) {
            return Map.of("content", "mock-llm:" + prompt, "model", model, "mock", true);
        }
        LlmClientApi.CompletionResult result = client.complete(
                baseUrl, apiKeyRef == null ? "" : apiKeyRef, model, messages, List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", result.content());
        out.put("model", model);
        out.put("promptTokens", result.promptTokens());
        out.put("completionTokens", result.completionTokens());
        return out;
    }

    private static String extractPrompt(Object input) {
        if (input == null) return "";
        if (input instanceof Map<?, ?> m) {
            if (m.containsKey("prompt")) return String.valueOf(m.get("prompt"));
            if (m.containsKey("text")) return String.valueOf(m.get("text"));
            if (m.containsKey("content")) return String.valueOf(m.get("content"));
        }
        return String.valueOf(input);
    }
}
