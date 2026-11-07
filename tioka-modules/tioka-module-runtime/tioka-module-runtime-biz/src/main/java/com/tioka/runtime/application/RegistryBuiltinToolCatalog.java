package com.tioka.runtime.application;

import com.tioka.agent.api.publish.BuiltinToolCatalog;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** 聚合 name=builtin 的 ToolProvider 作为内置工具注册表（AST-02）。 */
@Component
public class RegistryBuiltinToolCatalog implements BuiltinToolCatalog {
    private final List<ToolProvider> providers;

    public RegistryBuiltinToolCatalog(List<ToolProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    @Override
    public boolean contains(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        for (ToolProvider p : providers) {
            if (!"builtin".equals(p.name())) {
                continue;
            }
            if (p.listTools(null).stream().anyMatch(t -> toolName.equals(t.name()))) {
                return true;
            }
        }
        return false;
    }
}
