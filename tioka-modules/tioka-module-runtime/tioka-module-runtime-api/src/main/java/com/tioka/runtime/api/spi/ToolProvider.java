
package com.tioka.runtime.api.spi;

import com.tioka.runtime.api.approval.ToolRisk;

import java.util.List;
import java.util.Map;

public interface ToolProvider {
    String name();
    List<ToolDescriptor> listTools(String agentVersionId);
    ToolResult invoke(String toolName, Map<String, Object> arguments);

    /** 带 AgentVersion 作用域的调用；默认回退无作用域 invoke。 */
    default ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> arguments) {
        return invoke(toolName, arguments);
    }

    /**
     * 工具描述。三参构造器默认 {@link ToolRisk#READ}；MCP 工具从 {@code mcp_tool.risk} 取值（默认 WRITE）。
     */
    record ToolDescriptor(String name, String description, Map<String, Object> inputSchema, ToolRisk risk) {
        public ToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
            this(name, description, inputSchema, ToolRisk.READ);
        }
    }

    record ToolResult(boolean ok, String content) {}
}
