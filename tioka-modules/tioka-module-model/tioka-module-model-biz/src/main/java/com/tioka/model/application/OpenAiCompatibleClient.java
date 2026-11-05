package com.tioka.model.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.model.api.LlmClientApi;
import com.tioka.system.api.spi.EgressGuard;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class OpenAiCompatibleClient implements LlmClientApi {
    private final SecretStore secretStore;
    private final EgressGuard egressGuard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final boolean mock;

    public OpenAiCompatibleClient(SecretStore secretStore,
                                  EgressGuard egressGuard,
                                  @Value("${tioka.model.mock:true}") boolean mock) {
        this.secretStore = secretStore;
        this.egressGuard = egressGuard;
        this.mock = mock;
    }

    @Override
    public CompletionResult complete(String baseUrl, String apiKeyRef, String model,
                                     List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools) throws Exception {
        if (isMockMode(baseUrl)) {
            return mockComplete(messages, tools);
        }
        return httpComplete(baseUrl, apiKeyRef, model, messages, tools, false, null);
    }

    @Override
    public CompletionResult streamComplete(String baseUrl, String apiKeyRef, String model,
                                           List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           Consumer<StreamDelta> onDelta) throws Exception {
        Consumer<StreamDelta> sink = onDelta == null ? d -> {} : onDelta;
        if (isMockMode(baseUrl)) {
            return mockStreamComplete(messages, tools, sink);
        }
        return httpComplete(baseUrl, apiKeyRef, model, messages, tools, true, sink);
    }

    private boolean isMockMode(String baseUrl) {
        return mock || baseUrl == null || baseUrl.isBlank() || "mock://openai".equals(baseUrl);
    }

    private CompletionResult mockStreamComplete(List<Map<String, Object>> messages,
                                                List<Map<String, Object>> tools,
                                                Consumer<StreamDelta> onDelta) {
        CompletionResult full = mockComplete(messages, tools);
        if (full.toolCalls() != null && !full.toolCalls().isEmpty()) {
            return full;
        }
        String content = full.content() == null ? "" : full.content();
        if (content.isEmpty()) {
            return full;
        }
        // 按字符真正多次回调，禁止 Runtime 再切块
        for (int i = 0; i < content.length(); i++) {
            onDelta.accept(StreamDelta.text(String.valueOf(content.charAt(i))));
        }
        return full;
    }

    private CompletionResult mockComplete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        String last = "";
        if (messages != null && !messages.isEmpty()) {
            Object c = messages.get(messages.size() - 1).get("content");
            last = c == null ? "" : String.valueOf(c);
        }
        if (tools != null && !tools.isEmpty() && last.toLowerCase().contains("echo")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
            String name = String.valueOf(function.get("name"));
            String args = mapper.createObjectNode().put("text", last).toString();
            return new CompletionResult("", List.of(new ToolCall("call_mock", name, args)), 12, 24);
        }
        return new CompletionResult("【mock】已收到：" + last, List.of(), 10, 20);
    }

    private CompletionResult httpComplete(String baseUrl, String apiKeyRef, String model,
                                          List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          boolean stream,
                                          Consumer<StreamDelta> onDelta) throws Exception {
        egressGuard.assertAllowed(baseUrl);
        String apiKey = resolveApiKey(apiKeyRef);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", mapper.valueToTree(messages));
        if (tools != null && !tools.isEmpty()) body.set("tools", mapper.valueToTree(tools));
        if (stream) body.put("stream", true);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        if (!stream) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("openai compatible error: " + resp.statusCode() + " " + resp.body());
            }
            return parse(resp.body());
        }

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 300) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("openai compatible stream error: " + resp.statusCode() + " " + err);
        }
        return parseSse(resp.body(), onDelta);
    }

    private CompletionResult parseSse(InputStream body, Consumer<StreamDelta> onDelta) throws Exception {
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallAcc> toolAcc = new HashMap<>();
        int promptTokens = 0;
        int completionTokens = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                JsonNode root = mapper.readTree(payload);
                JsonNode choice = root.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                if (delta.hasNonNull("content")) {
                    String piece = delta.path("content").asText("");
                    if (!piece.isEmpty()) {
                        content.append(piece);
                        onDelta.accept(StreamDelta.text(piece));
                    }
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        int idx = tc.path("index").asInt(0);
                        ToolCallAcc acc = toolAcc.computeIfAbsent(idx, i -> new ToolCallAcc());
                        if (tc.hasNonNull("id")) acc.id = tc.path("id").asText(acc.id);
                        JsonNode fn = tc.path("function");
                        if (fn.hasNonNull("name")) acc.name = fn.path("name").asText(acc.name);
                        if (fn.has("arguments")) acc.arguments.append(fn.path("arguments").asText(""));
                    }
                }
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                }
            }
        }

        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < toolAcc.size(); i++) {
            ToolCallAcc acc = toolAcc.get(i);
            if (acc == null) continue;
            calls.add(new ToolCall(
                    acc.id == null ? "call_" + i : acc.id,
                    acc.name == null ? "" : acc.name,
                    acc.arguments.length() == 0 ? "{}" : acc.arguments.toString()
            ));
        }
        if (!toolAcc.isEmpty() && calls.isEmpty()) {
            for (ToolCallAcc acc : toolAcc.values()) {
                calls.add(new ToolCall(
                        acc.id == null ? "call_0" : acc.id,
                        acc.name == null ? "" : acc.name,
                        acc.arguments.length() == 0 ? "{}" : acc.arguments.toString()
                ));
            }
        }
        if (promptTokens > 0 || completionTokens > 0) {
            onDelta.accept(new StreamDelta(null, null, promptTokens, completionTokens));
        }
        return new CompletionResult(content.toString(), calls, promptTokens, completionTokens);
    }

    private String resolveApiKey(String apiKeyRef) {
        String ns = "model-provider";
        String key = apiKeyRef;
        if (apiKeyRef != null && apiKeyRef.contains("::")) {
            String[] parts = apiKeyRef.split("::", 2);
            ns = parts[0];
            key = parts[1];
        }
        String apiKey = secretStore.get(ns, key);
        return apiKey == null ? "" : apiKey;
    }

    private CompletionResult parse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode choice = root.path("choices").path(0).path("message");
        String content = choice.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = choice.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                calls.add(new ToolCall(
                        tc.path("id").asText(),
                        tc.path("function").path("name").asText(),
                        tc.path("function").path("arguments").asText("{}")
                ));
            }
        }
        return new CompletionResult(content, calls,
                root.path("usage").path("prompt_tokens").asInt(0),
                root.path("usage").path("completion_tokens").asInt(0));
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
