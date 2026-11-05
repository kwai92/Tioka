
package com.tioka.runtime.api.spi;

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

    record ToolDescriptor(String name, String description, Map<String, Object> inputSchema) {}
    record ToolResult(boolean ok, String content) {}
}
