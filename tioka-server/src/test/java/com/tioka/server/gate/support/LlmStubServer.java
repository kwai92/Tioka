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
 * ENG-04：OpenAI 兼容 {@code /chat/completions} 录制回放桩。
 * 按请求头 {@code X-Tioka-Script} 选剧本；未匹配返回 500。
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

    private Dispatcher dispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
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
                if (n >= script.calls.size()) {
                    return new MockResponse().setResponseCode(500).setBody("script exhausted: " + scriptName);
                }
                Call call = script.calls.get(n);
                boolean stream = false;
                try {
                    JsonNode body = json.readTree(request.getBody().readUtf8());
                    stream = body.path("stream").asBoolean(false);
                } catch (Exception ignored) {
                }
                try {
                    return stream ? streamResponse(call) : jsonResponse(call);
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
                }
            }
        };
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
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 1);
        usage.put("completion_tokens", 1);
        return root;
    }

    private ObjectNode baseChunk() {
        ObjectNode root = json.createObjectNode();
        root.put("id", "chatcmpl_stub");
        root.put("object", "chat.completion.chunk");
        return root;
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
    }

    public static final class Call {
        public String content;
        public Integer stream_chunk_chars;
        public Long chunk_delay_ms;
        public List<ToolCallSpec> tool_calls;
    }

    public static final class ToolCallSpec {
        public String name;
        public Map<String, Object> arguments;
    }
}
