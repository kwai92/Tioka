package com.tioka.mcp.application;

import com.tioka.agent.api.AgentQueryApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.mcp.domain.McpStore;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 AgentVersion 快照内的 MCP Grant 工具暴露给 Runtime ToolRegistry。
 */
@Component
public class McpToolProvider implements ToolProvider {
    private final McpAdminService mcpAdminService;
    private final AgentQueryApi agentQueryApi;

    public McpToolProvider(McpAdminService mcpAdminService, AgentQueryApi agentQueryApi) {
        this.mcpAdminService = mcpAdminService;
        this.agentQueryApi = agentQueryApi;
    }

    @Override
    public String name() { return "mcp"; }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        if (agentVersionId == null || agentVersionId.isBlank()) return List.of();
        List<String> grantIds = grantIdsOf(agentVersionId);
        List<McpStore.Tool> tools = mcpAdminService.toolsForGrantIds(grantIds);
        List<ToolDescriptor> out = new ArrayList<>();
        for (McpStore.Tool t : tools) {
            out.add(new ToolDescriptor(t.name(), t.description(), t.inputSchema()));
        }
        return out;
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
        var r = mcpAdminService.callToolWithGrantIds(grantIdsOf(agentVersionId), toolName, arguments);
        return new ToolResult(r.ok(), r.content());
    }

    private List<String> grantIdsOf(String agentVersionId) {
        AgentQueryApi.AgentVersionView v = agentQueryApi.requirePublished(agentVersionId);
        return v.mcpGrantIds() == null ? List.of() : v.mcpGrantIds();
    }
}
