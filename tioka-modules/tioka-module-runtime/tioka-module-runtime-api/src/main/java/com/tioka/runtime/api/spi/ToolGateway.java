package com.tioka.runtime.api.spi;

import java.util.List;
import java.util.Map;

/**
 * 执行面唯一工具入口（ADR-014 D3）。
 */
public interface ToolGateway {
    List<ToolProvider.ToolDescriptor> listTools(SessionToolContext ctx);

    ToolProvider.ToolResult call(SessionToolContext ctx, String toolName, Map<String, Object> args);

    List<Map<String, Object>> asOpenAiTools(SessionToolContext ctx);

    record SessionToolContext(String sessionId, String agentVersionId, String workspaceId, String principalId) {}
}
