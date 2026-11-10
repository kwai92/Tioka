package com.tioka.flow.application.exec;

import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flow MCP 节点：必须经 {@link ToolGateway}（审批 / 限流 / 审计），禁止直调 McpToolCallApi。
 */
@Component
public class McpNodeExecutor implements NodeExecutor {
    private final ObjectProvider<ToolGateway> toolGateway;

    public McpNodeExecutor(ObjectProvider<ToolGateway> toolGateway) {
        this.toolGateway = toolGateway;
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

        ToolGateway gateway = toolGateway.getIfAvailable();
        if (gateway == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", "ToolGateway unavailable");
            err.put("tool", toolName);
            return err;
        }
        if (serverId == null || serverId.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", "MCP node 必须指定 config.serverId");
            err.put("tool", toolName);
            return err;
        }

        Map<String, Object> agentToolConfig = Map.of(
                "mcp", List.of(Map.of("serverId", serverId, "allowlist", List.of(toolName))));
        String runId = ctx.runId() == null ? "flow" : ctx.runId();
        String nodeRunId = ctx.nodeRunId() == null ? runId : ctx.nodeRunId();
        ToolGateway.ToolCallContext toolCtx = ToolCallContexts.forTurn(
                ctx.runAs(),
                "flow:" + runId,
                nodeRunId,
                "flow-mcp",
                "flow",
                List.of(toolName),
                agentToolConfig,
                ctx.grantId());
        ToolProvider.ToolResult result = gateway.call(toolCtx, toolName, args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("content", result.content());
        out.put("tool", toolName);
        return out;
    }
}
