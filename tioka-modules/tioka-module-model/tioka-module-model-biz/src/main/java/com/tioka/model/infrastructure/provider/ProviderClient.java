package com.tioka.model.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 Provider HTTP 客户端（原 OpenAiCompatibleClient）。仅 model-biz 内部使用。
 */
@Component
public class ProviderClient {
    private final SecretStore secretStore;
    private final EgressClient egress;
    private final ObjectMapper mapper;
    private final boolean mock;
    private final int mockEmbeddingDim;

    public ProviderClient(SecretStore secretStore,
                          EgressClient egress,
                          @Value("${tioka.model.mock:true}") boolean mock,
                          @Value("${tioka.knowledge.embedding-dim:1024}") int mockEmbeddingDim,
                          ObjectMapper mapper) {
        this.mapper = mapper;
        this.secretStore = secretStore;
        this.egress = egress;
        this.mock = mock;
        this.mockEmbeddingDim = Math.max(1, mockEmbeddingDim);
    }

    public CompletionResult complete(String baseUrl, String apiKeyRef, String model,
                                     List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools) throws Exception {
        if (isMockMode(baseUrl)) {
            return mockComplete(messages, tools);
        }
        return httpComplete(baseUrl, apiKeyRef, model, messages, tools, false, null);
    }

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

    public List<float[]> embed(String baseUrl, String apiKeyRef, String model, Integer embeddingDim,
                               List<String> inputs) throws Exception {
        int dim = embeddingDim == null ? mockEmbeddingDim : Math.max(1, embeddingDim);
        if (isMockMode(baseUrl)) {
            List<float[]> out = new ArrayList<>();
            for (String text : inputs == null ? List.<String>of() : inputs) {
                out.add(hashEmbed(text, dim));
            }
            return out;
        }
        String apiKey = resolveApiKey(apiKeyRef);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("input", mapper.valueToTree(inputs == null ? List.of() : inputs));
        body.put("dimensions", dim);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + "/embeddings"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = egress.send(req);
        if (resp.statusCode() >= 300) {
            throw toHttpException(resp.statusCode(), resp.body(), retryAfterHeader(resp));
        }
        JsonNode data = mapper.readTree(resp.body()).path("data");
        List<float[]> vectors = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                JsonNode emb = item.path("embedding");
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    v[i] = (float) emb.get(i).asDouble();
                }
                vectors.add(fitDim(v, dim));
            }
        }
        return vectors;
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
            return new CompletionResult("", List.of(new ToolCall("call_mock", name, args)), 12, 24, null);
        }
        return new CompletionResult("【mock】已收到：" + last, List.of(), 10, 20, null);
    }

    private CompletionResult httpComplete(String baseUrl, String apiKeyRef, String model,
                                          List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          boolean stream,
                                          Consumer<StreamDelta> onDelta) throws Exception {
        String apiKey = resolveApiKey(apiKeyRef);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", mapper.valueToTree(messages));
        if (tools != null && !tools.isEmpty()) body.set("tools", mapper.valueToTree(tools));
        if (stream) body.put("stream", true);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json");
        String script = System.getProperty("tioka.llm.script");
        if (script != null && !script.isBlank()) {
            reqBuilder.header("X-Tioka-Script", script);
        }
        HttpRequest req = reqBuilder
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        try {
            if (!stream) {
                HttpResponse<String> resp = egress.send(req);
                if (resp.statusCode() >= 300) {
                    throw toHttpException(resp.statusCode(), resp.body(), retryAfterHeader(resp));
                }
                return parse(resp.body());
            }

            HttpResponse<InputStream> resp = egress.sendStreaming(req);
            if (resp.statusCode() >= 300) {
                byte[] raw = resp.body().readAllBytes();
                String err = new String(raw, StandardCharsets.UTF_8);
                // ENG-04b fail after_chars：错误响应体可含已发出的 SSE 增量
                if (looksLikeSse(err)) {
                    parseSse(new java.io.ByteArrayInputStream(raw), onDelta);
                }
                throw toHttpException(resp.statusCode(), err, retryAfterHeader(resp));
            }
            return parseSse(resp.body(), onDelta);
        } catch (ProviderHttpException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw ProviderHttpException.transport("模型调用超时: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw ProviderHttpException.transport("模型连接失败: " + e.getMessage(), e);
        }
    }

    private static String retryAfterHeader(HttpResponse<?> resp) {
        return resp.headers().firstValue("Retry-After").orElse(null);
    }

    private static ProviderHttpException toHttpException(int status, String body, String retryAfter) {
        boolean switchable = ProviderHttpException.isSwitchableStatus(status);
        if (!switchable && !ProviderHttpException.isNonSwitchableStatus(status)) {
            // 其他 4xx 视为不可切换，与 400 族一致
            switchable = false;
        }
        Long retryAfterMs = status == 429 ? ProviderHttpException.parseRetryAfterMs(retryAfter) : null;
        return new ProviderHttpException(status,
                "openai compatible error: " + status + " " + (body == null ? "" : body),
                switchable, retryAfterMs);
    }

    private static ProviderHttpException toHttpException(int status, String body) {
        return toHttpException(status, body, null);
    }

    private static boolean looksLikeSse(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String t = body.trim();
        return t.startsWith("data:") || t.contains("\ndata:");
    }

    private CompletionResult parseSse(InputStream body, Consumer<StreamDelta> onDelta) throws Exception {
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallAcc> toolAcc = new HashMap<>();
        int promptTokens = 0;
        int completionTokens = 0;
        String finishReason = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                JsonNode root = mapper.readTree(payload);
                JsonNode choice = root.path("choices").path(0);
                if (choice.hasNonNull("finish_reason")) {
                    finishReason = choice.path("finish_reason").asText(finishReason);
                }
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
        return new CompletionResult(content.toString(), calls, promptTokens, completionTokens, finishReason);
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
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText("");
        String finishReason = choice.path("finish_reason").asText(null);
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
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
                root.path("usage").path("completion_tokens").asInt(0),
                finishReason);
    }

    private static float[] hashEmbed(String text, int dim) {
        float[] v = new float[dim];
        if (text == null || text.isBlank()) {
            return v;
        }
        byte[] bytes = text.toLowerCase().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            v[Math.floorMod(bytes[i] + i, v.length)] += 1.0f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= (float) norm;
            }
        }
        return v;
    }

    private static float[] fitDim(float[] src, int dim) {
        if (src.length == dim) {
            return src;
        }
        float[] out = new float[dim];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, dim));
        return out;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record ToolCall(String id, String name, String argumentsJson) {}

    public record CompletionResult(String content, List<ToolCall> toolCalls,
                                   int promptTokens, int completionTokens, String finishReason) {}

    public record StreamDelta(String textDelta, List<ToolCall> toolCalls,
                              Integer promptTokens, Integer completionTokens) {
        public static StreamDelta text(String delta) {
            return new StreamDelta(delta, null, null, null);
        }
    }

    private static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
