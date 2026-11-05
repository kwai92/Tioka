package com.tioka.mcp.api;

import java.util.Map;

/** Flow MCP 节点调用入口（避免 biz→biz）。 */
public interface McpToolCallApi {
    CallResult callTool(String serverId, String toolName, Map<String, Object> arguments);

    CallResult callToolByName(String toolName, Map<String, Object> arguments);

    record CallResult(boolean ok, String content) {}
}
