package com.tioka.mcp.application;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
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
 * 将 Agent 版本快照内的 MCP 工具暴露给 Runtime ToolRegistry（AST-01：读 AgentSpec.tools.mcp）。
 */
@Component
public class McpToolProvider implements ToolProvider {
    private final McpAdminService mcpAdminService;
    private final AgentSnapshotApi snapshots;
    private final McpStore store;

    public McpToolProvider(McpAdminService mcpAdminService, AgentSnapshotApi snapshots, McpStore store) {
        this.mcpAdminService = mcpAdminService;
        this.snapshots = snapshots;
        this.store = store;
    }

    @Override
    public String name() { return "mcp"; }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        if (agentVersionId == null || agentVersionId.isBlank()) return List.of();
        List<McpAdminService.McpBinding> bindings;
        try {
            bindings = bindingsOf(agentVersionId);
        } catch (BizException e) {
            // 解析扫描时版本可能尚未绑定 MCP；视为无工具而非致命错误
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
        throw new BizException(ErrorCode.FORBIDDEN, "MCP 工具必须带 agentVersionId 调用");
    }

    @Override
    public ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> arguments) {
        if (agentVersionId == null || agentVersionId.isBlank()) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少 agentVersionId，拒绝 MCP 工具调用");
        }
        var r = mcpAdminService.callToolWithMcpBindings(bindingsOf(agentVersionId), toolName, arguments);
        return new ToolResult(r.ok(), r.content());
    }

    @Override
    public Optional<String> mcpServerId(String agentVersionId, String toolName) {
        for (McpAdminService.McpBinding b : bindingsOf(agentVersionId)) {
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
    public Optional<String> egressUrl(String agentVersionId, String toolName) {
        return mcpServerId(agentVersionId, toolName).flatMap(sid ->
                store.getServer(sid).map(McpStore.McpServer::baseUrl)
                        .filter(u -> u != null && !u.isBlank() && !u.startsWith("mock://")));
    }

    private List<McpAdminService.McpBinding> bindingsOf(String agentVersionId) {
        AgentSpec.ToolSet tools = snapshots.get(agentVersionId).spec().tools();
        if (tools == null || tools.mcp() == null) return List.of();
        return tools.mcp().stream()
                .map(m -> new McpAdminService.McpBinding(m.serverId(), m.allowlist()))
                .toList();
    }
}
