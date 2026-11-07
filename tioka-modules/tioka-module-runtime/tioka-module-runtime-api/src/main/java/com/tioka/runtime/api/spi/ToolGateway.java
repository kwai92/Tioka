package com.tioka.runtime.api.spi;

import com.tioka.framework.security.Principal;

import java.util.List;
import java.util.Map;

/**
 * 执行面唯一工具入口（ADR-014 D3；CON-01 鉴权 / 限流 / 审计）。
 *
 * <p>{@link ToolCallContext} 只经 {@link ToolCallContexts} 工厂构造（M1-00 F3）。
 */
public interface ToolGateway {
    List<ToolProvider.ToolDescriptor> listTools(ToolCallContext ctx);

    ToolProvider.ToolResult call(ToolCallContext ctx, String toolName, Map<String, Object> args);

    List<Map<String, Object>> asOpenAiTools(ToolCallContext ctx);

    /**
     * 工具调用上下文（取代 SessionToolContext）。
     *
     * @param agentToolConfig 含 {@code mcp}（{@code [{serverId, allowlist[]}]} = McpBinding），由 Runtime 从 Agent 快照注入
     */
    record ToolCallContext(Principal principal, String threadId, String turnId, String agentVersionId,
                           String origin, List<String> toolAllowList, Map<String, Object> agentToolConfig) {}
}
