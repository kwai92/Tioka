package com.tioka.server.gate.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ENG-04b：MCP JSON-RPC 桩（initialize / tools/list / tools/call）。
 */
public final class McpStubServer implements AutoCloseable {
    private final MockWebServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final List<CallRecord> calls = new CopyOnWriteArrayList<>();

    private McpStubServer(MockWebServer server) {
        this.server = server;
    }

    public static McpStubServer start() {
        try {
            McpStubServer stub = new McpStubServer(new MockWebServer());
            stub.server.setDispatcher(stub.dispatcher());
            stub.server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    public List<CallRecord> calls() {
        return List.copyOf(calls);
    }

    public void clearCalls() {
        calls.clear();
    }

    private Dispatcher dispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    JsonNode body = json.readTree(request.getBody().readUtf8());
                    String method = body.path("method").asText("");
                    JsonNode id = body.get("id");
                    return switch (method) {
                        case "initialize" -> rpcOk(id, initializeResult());
                        case "tools/list" -> rpcOk(id, toolsListResult());
                        case "tools/call" -> handleCall(id, body.path("params"));
                        case "notifications/initialized" -> new MockResponse().setResponseCode(202);
                        default -> rpcError(id, -32601, "Method not found: " + method);
                    };
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
                }
            }
        };
    }

    private MockResponse handleCall(JsonNode id, JsonNode params) throws Exception {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        calls.add(new CallRecord(name, args == null || args.isMissingNode() ? Map.of() : json.convertValue(args, Map.class)));
        if ("erp.slow".equals(name)) {
            Thread.sleep(3000);
        }
        ObjectNode result = json.createObjectNode();
        result.put("isError", false);
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", switch (name) {
            case "erp.query" -> "query-ok";
            case "erp.update" -> "update-ok";
            case "erp.slow" -> "slow-ok";
            case "erp.deleteCustomer" -> "delete-ok";
            default -> "unknown-tool:" + name;
        });
        if (!List.of("erp.query", "erp.update", "erp.slow", "erp.deleteCustomer").contains(name)) {
            result.put("isError", true);
        }
        return rpcOk(id, result);
    }

    private ObjectNode initializeResult() {
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "mcp-stub");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = json.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(tool("erp.query", "READ：查询 ERP", "READ"));
        tools.add(tool("erp.update", "WRITE：更新 ERP", "WRITE"));
        tools.add(tool("erp.slow", "慢工具（3s），用于崩溃与取消", "READ"));
        tools.add(tool("erp.deleteCustomer", "WRITE：删除客户（授权书禁区，C-19 G3 映射 biz.erp.deleteCustomer）", "WRITE"));
        return result;
    }

    private ObjectNode tool(String name, String description, String risk) {
        ObjectNode t = json.createObjectNode();
        t.put("name", name);
        t.put("description", description + " [" + risk + "]");
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        // C-19 G3：策略可求值参数（resourceAttrs 直传 tool args 原始键）
        ObjectNode props = schema.putObject("properties");
        props.putObject("q").put("type", "string");
        props.putObject("id").put("type", "string");
        props.putObject("customerLevel").put("type", "string");
        props.putObject("amountMicros").put("type", "number");
        return t;
    }

    private MockResponse rpcOk(JsonNode id, ObjectNode result) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            root.set("id", id);
        }
        root.set("result", result);
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(root));
    }

    private MockResponse rpcError(JsonNode id, int code, String message) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            root.set("id", id);
        }
        ObjectNode err = root.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(root));
    }

    @Override
    public void close() {
        try {
            server.shutdown();
        } catch (IOException ignored) {
        }
    }

    public record CallRecord(String name, Map<String, Object> arguments) {}
}
