package com.tioka.mcp.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.mcp.domain.McpStore;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Agent 快照内的 MCP 工具暴露给 Runtime ToolRegistry。
 * ENG-06：只读 {@code agentToolConfig.mcpGrants}，不再依赖 agent-api。
 */
@Component
public class McpToolProvider implements ToolProvider {
    private final McpAdminService mcpAdminService;
    private final McpStore store;

    public McpToolProvider(McpAdminService mcpAdminService, McpStore store) {
        this.mcpAdminService = mcpAdminService;
        this.store = store;
    }

    @Override
    public String name() { return "mcp"; }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of();
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId, Map<String, Object> agentToolConfig) {
        List<McpAdminService.McpBinding> bindings = bindingsOf(agentToolConfig);
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<McpStore.Tool> tools = mcpAdminService.toolsForMcpBindings(bindings);
        List<ToolDescriptor> out = new ArrayList<>();
        for (McpStore.Tool t : tools) {
            out.add(ToolDescriptor.builder()
                    .name(t.name())
                    .description(t.description())
                    .inputSchema(t.inputSchema())
                    .risk(parseRisk(t.risk()))
                    .build());
        }
        return out;
    }

    private static ToolRisk parseRisk(String raw) {
        if (raw == null || raw.isBlank()) {
            return ToolRisk.WRITE;
        }
        try {
            return ToolRisk.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return ToolRisk.WRITE;
        }
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        throw new BizException(ErrorCode.FORBIDDEN, "MCP 工具必须带 agentToolConfig 调用");
    }

    @Override
    public ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> arguments) {
        throw new BizException(ErrorCode.FORBIDDEN, "MCP 工具必须带 agentToolConfig 调用");
    }

    @Override
    public ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> arguments,
                             Map<String, Object> agentToolConfig) {
        var r = mcpAdminService.callToolWithMcpBindings(bindingsOf(agentToolConfig), toolName, arguments);
        return new ToolResult(r.ok(), r.content());
    }

    @Override
    public Optional<String> mcpServerId(String agentVersionId, String toolName) {
        return Optional.empty();
    }

    @Override
    public Optional<String> mcpServerId(String agentVersionId, String toolName,
                                        Map<String, Object> agentToolConfig) {
        for (McpAdminService.McpBinding b : bindingsOf(agentToolConfig)) {
            if (b == null || b.serverId() == null) continue;
            if (b.allowlist() != null && !b.allowlist().isEmpty() && !b.allowlist().contains(toolName)) {
                continue;
            }
            for (McpStore.Tool t : store.tools(b.serverId())) {
                if (Objects.equals(t.name(), toolName)) {
                    return Optional.of(b.serverId());
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> egressUrl(String agentVersionId, String toolName,
                                      Map<String, Object> agentToolConfig) {
        return mcpServerId(agentVersionId, toolName, agentToolConfig).flatMap(sid ->
                store.getServer(sid).map(McpStore.McpServer::baseUrl)
                        .filter(u -> u != null && !u.isBlank() && !u.startsWith("mock://")));
    }

    @SuppressWarnings("unchecked")
    static List<McpAdminService.McpBinding> bindingsOf(Map<String, Object> agentToolConfig) {
        if (agentToolConfig == null || agentToolConfig.isEmpty()) {
            return List.of();
        }
        Object raw = agentToolConfig.get("mcpGrants");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<McpAdminService.McpBinding> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object sid = m.get("serverId");
            if (sid == null || String.valueOf(sid).isBlank()) {
                continue;
            }
            Object al = m.get("allowlist");
            List<String> allow = List.of();
            if (al instanceof List<?> allowRaw) {
                allow = allowRaw.stream().filter(Objects::nonNull).map(String::valueOf).toList();
            }
            out.add(new McpAdminService.McpBinding(String.valueOf(sid), allow));
        }
        return out;
    }
}
