package com.tioka.server.gate.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ENG-04 / ENG-04b：OpenAI 兼容 {@code /chat/completions} 录制回放桩。
 * 按请求头 {@code X-Tioka-Script} 选剧本；支持 {@code fail}/{@code delay_ms}/{@code usage}。
 */
public final class LlmStubServer implements AutoCloseable {
    private final MockWebServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    private LlmStubServer(MockWebServer server) {
        this.server = server;
    }

    public static LlmStubServer start() {
        try {
            LlmStubServer stub = new LlmStubServer(new MockWebServer());
            stub.loadClasspath("llm-scripts/three-tools.yaml", "three-tools");
            stub.loadClasspath("llm-scripts/slow-pong.yaml", "slow-pong");
            stub.loadClasspath("llm-scripts/mcp-three-steps.yaml", "mcp-three-steps");
            stub.loadClasspath("llm-scripts/mcp-slow-cancel.yaml", "mcp-slow-cancel");
            stub.loadClasspath("llm-scripts/write-needs-approval.yaml", "write-needs-approval");
            stub.loadClasspath("llm-scripts/always-write-approval.yaml", "always-write-approval");
            stub.loadClasspath("llm-scripts/reject-then-answer.yaml", "reject-then-answer");
            stub.loadClasspath("llm-scripts/mixed-read-write.yaml", "mixed-read-write");
            stub.loadClasspath("llm-scripts/failover-mid-stream.yaml", "failover-mid-stream");
            stub.loadClasspath("llm-scripts/failover-before-delta.yaml", "failover-before-delta");
            stub.loadClasspath("llm-scripts/failover-400.yaml", "failover-400");
            stub.loadClasspath("llm-scripts/failover-exhausted.yaml", "failover-exhausted");
            stub.loadClasspath("llm-scripts/backoff-retry.yaml", "backoff-retry");
            stub.loadClasspath("llm-scripts/kb-search.yaml", "kb-search");
            stub.loadClasspath("llm-scripts/chat-async-delay.yaml", "chat-async-delay");
            stub.loadClasspath("llm-scripts/chat-index-preview.yaml", "chat-index-preview");
            stub.server.setDispatcher(stub.dispatcher());
            stub.server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return server.url("/v1").toString().replaceAll("/$", "");
    }

    public void loadClasspath(String resource, String name) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            scripts.put(name, yaml.readValue(in, Script.class));
        }
    }

    /** 测试运行时替换/注入剧本（如 KNW-02 动态填入 kbIds）。 */
    public void putScript(String name, Script script) {
        scripts.put(name, script);
        counters.remove(name);
    }

    public void resetCounter(String name) {
        counters.remove(name);
    }

    private Dispatcher dispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.contains("/embeddings")) {
                    return embeddingResponse(request);
                }
                if (!path.contains("/chat/completions")) {
                    return new MockResponse().setResponseCode(404);
                }
                String scriptName = request.getHeader("X-Tioka-Script");
                if (scriptName == null || scriptName.isBlank()) {
                    scriptName = System.getProperty("tioka.llm.script", "three-tools");
                }
                Script script = scripts.get(scriptName);
                if (script == null || script.calls == null || script.calls.isEmpty()) {
                    return new MockResponse().setResponseCode(500).setBody("unknown script: " + scriptName);
                }
                int n = counters.computeIfAbsent(scriptName, k -> new AtomicInteger()).getAndIncrement();
                int idx = Boolean.TRUE.equals(script.loop)
                        ? Math.floorMod(n, script.calls.size())
                        : n;
                if (idx >= script.calls.size()) {
                    return new MockResponse().setResponseCode(500).setBody("script exhausted: " + scriptName);
                }
                Call call = script.calls.get(idx);
                boolean stream = false;
                try {
                    JsonNode body = json.readTree(request.getBody().readUtf8());
                    stream = body.path("stream").asBoolean(false);
                } catch (Exception ignored) {
                }
                try {
                    if (call.delay_ms != null && call.delay_ms > 0) {
                        Thread.sleep(call.delay_ms);
                    }
                    if (call.fail != null) {
                        return failResponse(call, stream);
                    }
                    return stream ? streamResponse(call) : jsonResponse(call);
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
                }
            }
        };
    }

    private MockResponse failResponse(Call call, boolean stream) throws Exception {
        int status = call.fail.status == null ? 500 : call.fail.status;
        int after = call.fail.after_chars == null ? 0 : call.fail.after_chars;
        if (!stream || after <= 0) {
            return new MockResponse().setResponseCode(status).setBody("{\"error\":\"scripted fail\"}");
        }
        Buffer buf = new Buffer();
        String content = call.content == null ? "x".repeat(after) : call.content;
        String piece = content.substring(0, Math.min(after, content.length()));
        ObjectNode root = baseChunk();
        root.putArray("choices").addObject().putObject("delta").put("content", piece);
        buf.writeUtf8("data: " + json.writeValueAsString(root) + "\n\n");
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(buf);
    }

    private MockResponse jsonResponse(Call call) throws Exception {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(completion(call)));
    }

    private MockResponse streamResponse(Call call) throws Exception {
        Buffer buf = new Buffer();
        if (call.tool_calls != null && !call.tool_calls.isEmpty()) {
            ObjectNode root = baseChunk();
            ObjectNode choice = root.putArray("choices").addObject();
            ObjectNode delta = choice.putObject("delta");
            ArrayNode tcs = delta.putArray("tool_calls");
            int i = 0;
            for (ToolCallSpec tc : call.tool_calls) {
                ObjectNode t = tcs.addObject();
                t.put("index", i);
                t.put("id", "call_" + (i + 1));
                ObjectNode fn = t.putObject("function");
                fn.put("name", tc.name);
                fn.put("arguments", json.writeValueAsString(tc.arguments == null ? Map.of() : tc.arguments));
                i++;
            }
            buf.writeUtf8("data: " + json.writeValueAsString(root) + "\n\n");
        } else {
            String content = call.content == null ? "" : call.content;
            int chunk = call.stream_chunk_chars == null || call.stream_chunk_chars <= 0
                    ? Math.max(1, content.length()) : call.stream_chunk_chars;
            long delay = call.chunk_delay_ms == null ? 0 : call.chunk_delay_ms;
            for (int i = 0; i < content.length(); i += chunk) {
                String piece = content.substring(i, Math.min(content.length(), i + chunk));
                ObjectNode root = baseChunk();
                root.putArray("choices").addObject().putObject("delta").put("content", piece);
                buf.writeUtf8("data: " + json.writeValueAsString(root) + "\n\n");
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
        }
        ObjectNode usageChunk = baseChunk();
        usageChunk.set("usage", usageNode(call));
        buf.writeUtf8("data: " + json.writeValueAsString(usageChunk) + "\n\n");
        buf.writeUtf8("data: [DONE]\n\n");
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(buf);
    }

    private ObjectNode completion(Call call) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("id", "chatcmpl_stub");
        root.put("object", "chat.completion");
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (call.tool_calls != null && !call.tool_calls.isEmpty()) {
            message.putNull("content");
            ArrayNode tcs = message.putArray("tool_calls");
            int i = 0;
            for (ToolCallSpec tc : call.tool_calls) {
                ObjectNode t = tcs.addObject();
                t.put("id", "call_" + (++i));
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tc.name);
                fn.put("arguments", json.writeValueAsString(tc.arguments == null ? Map.of() : tc.arguments));
            }
            choice.put("finish_reason", "tool_calls");
        } else {
            message.put("content", call.content == null ? "" : call.content);
            choice.put("finish_reason", "stop");
        }
        root.set("usage", usageNode(call));
        return root;
    }

    private ObjectNode usageNode(Call call) {
        ObjectNode usage = json.createObjectNode();
        int prompt;
        int completion;
        if (call.usage != null) {
            prompt = call.usage.prompt == null ? estimateChars(call) : call.usage.prompt;
            completion = call.usage.completion == null ? estimateChars(call) : call.usage.completion;
        } else {
            int est = estimateChars(call);
            prompt = Math.max(1, est);
            completion = Math.max(1, est);
        }
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        usage.put("total_tokens", prompt + completion);
        return usage;
    }

    private static int estimateChars(Call call) {
        if (call.content != null && !call.content.isBlank()) {
            return call.content.length();
        }
        if (call.tool_calls != null) {
            return call.tool_calls.size() * 8;
        }
        return 1;
    }

    private ObjectNode baseChunk() {
        ObjectNode root = json.createObjectNode();
        root.put("id", "chatcmpl_stub");
        root.put("object", "chat.completion.chunk");
        return root;
    }

    /** KNW-01：OpenAI 兼容 /embeddings，按 dimensions（缺省 32）返回确定性哈希向量。 */
    private MockResponse embeddingResponse(RecordedRequest request) {
        try {
            JsonNode body = json.readTree(request.getBody().readUtf8());
            int dim = body.path("dimensions").asInt(32);
            if (dim <= 0) {
                dim = 32;
            }
            ArrayNode inputs = body.path("input").isArray()
                    ? (ArrayNode) body.path("input")
                    : json.createArrayNode().add(body.path("input").asText(""));
            ObjectNode root = json.createObjectNode();
            root.put("object", "list");
            ArrayNode data = root.putArray("data");
            for (int i = 0; i < inputs.size(); i++) {
                String text = inputs.get(i).asText("");
                ObjectNode item = data.addObject();
                item.put("object", "embedding");
                item.put("index", i);
                ArrayNode emb = item.putArray("embedding");
                float[] v = hashEmbed(text, dim);
                for (float x : v) {
                    emb.add(x);
                }
            }
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", Math.max(1, inputs.size()));
            usage.put("total_tokens", Math.max(1, inputs.size()));
            return new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(json.writeValueAsString(root));
        } catch (Exception e) {
            return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
        }
    }

    private static float[] hashEmbed(String text, int dim) {
        float[] v = new float[dim];
        if (text == null || text.isBlank()) {
            return v;
        }
        byte[] bytes = text.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            v[Math.floorMod(bytes[i] + i, dim)] += 1.0f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) {
                v[i] /= (float) norm;
            }
        }
        return v;
    }

    @Override
    public void close() {
        try {
            server.shutdown();
        } catch (IOException ignored) {
        }
    }

    public static final class Script {
        public List<Call> calls = new ArrayList<>();
        /** 耗尽后按模循环（并发闸门用）。 */
        public Boolean loop;
    }

    public static final class Call {
        public String content;
        public Integer stream_chunk_chars;
        public Long chunk_delay_ms;
        public Long delay_ms;
        public FailSpec fail;
        public UsageSpec usage;
        public List<ToolCallSpec> tool_calls;
    }

    public static final class FailSpec {
        public Integer status;
        public Integer after_chars;
    }

    public static final class UsageSpec {
        public Integer prompt;
        public Integer completion;
    }

    public static final class ToolCallSpec {
        public String name;
        public Map<String, Object> arguments;
    }
}
