package com.tioka.runtime.api.spi;

import java.util.Map;

/**
 * 事务型工具调用入参（ADR-033 {@code TransactionalTool}）。
 */
public record ToolInvocation(String toolName, Map<String, Object> arguments) {
    public ToolInvocation {
        if (arguments == null) {
            arguments = Map.of();
        }
    }
}
