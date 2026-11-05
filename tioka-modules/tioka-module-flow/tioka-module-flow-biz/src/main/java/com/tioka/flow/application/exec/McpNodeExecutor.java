package com.tioka.flow.application.exec;

import com.tioka.mcp.api.McpToolCallApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class McpNodeExecutor implements NodeExecutor {
    private final ObjectProvider<McpToolCallApi> mcp;

    public McpNodeExecutor(ObjectProvider<McpToolCallApi> mcp) {
        this.mcp = mcp;
    }

    @Override
    public String type() { return "MCP"; }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String serverId = config.get("serverId") == null ? null : String.valueOf(config.get("serverId"));
        String toolName = String.valueOf(config.getOrDefault("tool", config.getOrDefault("toolName", "mcp_echo")));
        Map<String, Object> args;
        if (config.get("arguments") instanceof Map<?, ?> m) {
            args = (Map<String, Object>) m;
        } else if (input instanceof Map<?, ?> m) {
            args = (Map<String, Object>) m;
        } else {
            args = Map.of("text", input == null ? "" : String.valueOf(input));
        }

        McpToolCallApi api = mcp.getIfAvailable();
        if (api == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", "McpToolCallApi unavailable");
            err.put("tool", toolName);
            return err;
        }
        McpToolCallApi.CallResult result = (serverId != null && !serverId.isBlank())
                ? api.callTool(serverId, toolName, args)
                : api.callToolByName(toolName, args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("content", result.content());
        out.put("tool", toolName);
        return out;
    }
}
