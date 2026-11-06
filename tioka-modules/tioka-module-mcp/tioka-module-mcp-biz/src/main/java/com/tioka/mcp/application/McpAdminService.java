package com.tioka.mcp.application;

import com.tioka.framework.id.Ids;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.mcp.api.McpToolCallApi;
import com.tioka.mcp.domain.McpStore;
import com.tioka.mcp.domain.McpStore.*;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class McpAdminService implements McpToolCallApi {
    private final SecretStore secretStore;
    private final McpStore store;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final boolean mockDiscovery;

    public McpAdminService(SecretStore secretStore, McpStore store,
                           @Value("${tioka.mcp.mock:true}") boolean mockDiscovery,
                                  ObjectMapper mapper) {
        this.mapper = mapper;
        this.secretStore = secretStore;
        this.store = store;
        this.mockDiscovery = mockDiscovery;
    }

    public McpServer register(String name, String baseUrl, String bearerToken) {
        String id = Ids.newId("mcp");
        String secretRef = null;
        if (bearerToken != null && !bearerToken.isBlank()) {
            secretRef = secretStore.put("mcp-header", id, bearerToken);
        }
        McpServer server = new McpServer(id, name, baseUrl, secretRef, "REGISTERED");
        store.putServer(server);
        return server;
    }

    public List<McpServer> listServers() {
        return store.servers();
    }

    public List<Grant> listGrants() {
        return store.grants();
    }

    public List<Tool> toolsOf(String serverId) {
        return store.tools(serverId);
    }

    /** FE-01：更新已登记工具的风险等级（V114 四态）。 */
    public Tool updateToolRisk(String serverId, String toolName, String risk) {
        if (store.getServer(serverId).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "mcp server 不存在");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "toolName 不能为空");
        }
        String normalized = normalizeRisk(risk);
        return store.updateToolRisk(serverId, toolName, normalized);
    }

    static String normalizeRisk(String risk) {
        if (risk == null || risk.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "risk 不能为空");
        }
        String trimmed = risk.trim();
        try {
            return com.tioka.runtime.api.approval.ToolRisk.valueOf(trimmed).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "risk 须为 READ|WRITE|DESTRUCTIVE|EXTERNAL_SEND");
        }
    }

    public List<Tool> refreshTools(String serverId) {
        McpServer server = store.getServer(serverId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "mcp server 不存在"));
        List<Tool> tools;
        if (mockDiscovery || server.baseUrl() == null || server.baseUrl().startsWith("mock://")) {
            tools = List.of(new Tool(
                    "mcp_echo",
                    "MCP mock echo tool",
                    Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text"))
            ));
        } else {
            tools = discoverHttp(server);
        }
        store.replaceTools(serverId, tools);
        store.putServer(new McpServer(server.id(), server.name(), server.baseUrl(), server.headerSecretRef(), "READY"));
        // 返回库中行（含 FE-01 保留的 risk），勿直接回传发现结果
        return store.tools(serverId);
    }

    private List<Tool> discoverHttp(McpServer server) {
        try {
            JsonNode root = postRpc(server, "tools/list", Map.of());
            List<Tool> tools = new ArrayList<>();
            for (JsonNode t : root.path("result").path("tools")) {
                tools.add(new Tool(
                        t.path("name").asText(),
                        t.path("description").asText(""),
                        mapper.convertValue(t.path("inputSchema"), Map.class)
                ));
            }
            return tools;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "MCP tools/list 失败: " + e.getMessage());
        }
    }

    /**
     * 调用 MCP 工具：mock 服务器回退本地 echo；否则 JSON-RPC tools/call。
     */
    @Override
    public McpToolCallApi.CallResult callTool(String serverId, String toolName, Map<String, Object> arguments) {
        McpServer server = store.getServer(serverId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "mcp server 不存在"));
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        if (mockDiscovery || server.baseUrl() == null || server.baseUrl().startsWith("mock://")) {
            if ("mcp_echo".equals(toolName)) {
                return new McpToolCallApi.CallResult(true, "mcp:" + String.valueOf(args.getOrDefault("text", "")));
            }
            return new McpToolCallApi.CallResult(false, "unknown mock tool: " + toolName);
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", args);
            JsonNode root = postRpc(server, "tools/call", params);
            if (root.has("error") && !root.get("error").isNull()) {
                return new McpToolCallApi.CallResult(false, root.path("error").toString());
            }
            JsonNode result = root.path("result");
            boolean isError = result.path("isError").asBoolean(false);
            StringBuilder text = new StringBuilder();
            for (JsonNode c : result.path("content")) {
                if ("text".equals(c.path("type").asText())) {
                    if (text.length() > 0) text.append('\n');
                    text.append(c.path("text").asText(""));
                }
            }
            if (text.length() == 0) {
                text.append(result.toString());
            }
            return new McpToolCallApi.CallResult(!isError, text.toString());
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "MCP tools/call 失败: " + e.getMessage());
        }
    }

    /** 按工具名在全部 Grant 中解析（Flow 等无 version 作用域场景）；mock=false 时禁止无 Grant 的 echo 兜底。 */
    @Override
    public McpToolCallApi.CallResult callToolByName(String toolName, Map<String, Object> arguments) {
        for (Grant g : store.grants()) {
            if (!g.allowlist().isEmpty() && !g.allowlist().contains(toolName)) continue;
            for (Tool t : store.tools(g.serverId())) {
                if (Objects.equals(t.name(), toolName)) {
                    return callTool(g.serverId(), toolName, arguments);
                }
            }
        }
        if (mockDiscovery && "mcp_echo".equals(toolName)) {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            return new McpToolCallApi.CallResult(true, "mcp:" + String.valueOf(args.getOrDefault("text", "")));
        }
        return new McpToolCallApi.CallResult(false, "unknown tool: " + toolName);
    }

    /** 仅解析指定 Grant 快照内的工具（AgentVersion.mcpGrantIds）。 */
    public List<Tool> toolsForGrantIds(List<String> grantIds) {
        if (grantIds == null || grantIds.isEmpty()) return List.of();
        List<Tool> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String grantId : grantIds) {
            Optional<Grant> g = store.getGrant(grantId);
            if (g.isEmpty()) continue;
            for (Tool t : store.tools(g.get().serverId())) {
                if (!g.get().allowlist().isEmpty() && !g.get().allowlist().contains(t.name())) continue;
                if (seen.add(t.name())) out.add(t);
            }
        }
        return out;
    }

    public McpToolCallApi.CallResult callToolWithGrantIds(List<String> grantIds, String toolName,
                                                         Map<String, Object> arguments) {
        if (grantIds == null || grantIds.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "工具未授权: " + toolName);
        }
        for (String grantId : grantIds) {
            Optional<Grant> opt = store.getGrant(grantId);
            if (opt.isEmpty()) continue;
            Grant g = opt.get();
            if (!g.allowlist().isEmpty() && !g.allowlist().contains(toolName)) continue;
            for (Tool t : store.tools(g.serverId())) {
                if (Objects.equals(t.name(), toolName)) {
                    return callTool(g.serverId(), toolName, arguments);
                }
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN, "工具未授权: " + toolName);
    }

    private JsonNode postRpc(McpServer server, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", UUID.randomUUID().toString());
        rpc.put("method", method);
        rpc.put("params", params);
        String body = mapper.writeValueAsString(rpc);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (server.headerSecretRef() != null) {
            String[] parts = server.headerSecretRef().split("::", 2);
            String token = secretStore.get(parts[0], parts[1]);
            if (token != null) b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String raw = resp.body() == null ? "" : resp.body();
        String json = extractJsonRpc(raw);
        return mapper.readTree(json);
    }

    /** 兼容 application/json 与 SSE data: 行。 */
    static String extractJsonRpc(String raw) {
        String t = raw.trim();
        if (t.startsWith("{")) return t;
        for (String line : t.split("\n")) {
            String s = line.trim();
            if (s.startsWith("data:")) {
                String payload = s.substring(5).trim();
                if (payload.startsWith("{")) return payload;
            }
        }
        return t;
    }

    public Grant grant(String agentId, String serverId, List<String> allowlist) {
        if (store.getServer(serverId).isEmpty()) throw new BizException(ErrorCode.NOT_FOUND, "server 不存在");
        String id = Ids.newId("gr");
        Grant g = new Grant(id, agentId, serverId, allowlist == null ? List.of() : allowlist);
        store.putGrant(g);
        return g;
    }

    public List<Tool> toolsForAgent(String agentId) {
        List<Tool> out = new ArrayList<>();
        for (Grant g : store.grants()) {
            if (!Objects.equals(g.agentId(), agentId)) continue;
            for (Tool t : store.tools(g.serverId())) {
                if (g.allowlist().isEmpty() || g.allowlist().contains(t.name())) {
                    out.add(t);
                }
            }
        }
        return out;
    }
}
