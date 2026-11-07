package com.tioka.agent.api.publish;

/**
 * 内置工具名注册表（AST-02）。由 Runtime 侧聚合 {@code ToolProvider.name()=="builtin"} 提供。
 */
public interface BuiltinToolCatalog {
    boolean contains(String toolName);
}
