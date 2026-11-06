package com.tioka.runtime.application;

import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {
    private final List<ToolProvider> providers;

    public ToolRegistry(List<ToolProvider> providers) {
        this.providers = providers;
    }

    public record ResolvedTool(ToolProvider provider, ToolProvider.ToolDescriptor descriptor) {}

    public Optional<ResolvedTool> resolve(String agentVersionId, String toolName) {
        if (agentVersionId == null || agentVersionId.isBlank() || toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        for (ToolProvider p : providers) {
            for (ToolProvider.ToolDescriptor d : p.listTools(agentVersionId)) {
                if (toolName.equals(d.name())) {
                    return Optional.of(new ResolvedTool(p, d));
                }
            }
        }
        return Optional.empty();
    }

    public List<ToolProvider.ToolDescriptor> listAll(String agentVersionId) {
        List<ToolProvider.ToolDescriptor> all = new ArrayList<>();
        for (ToolProvider p : providers) {
            all.addAll(p.listTools(agentVersionId));
        }
        return all;
    }

    public ToolProvider.ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        return resolve(agentVersionId, toolName)
                .map(r -> r.provider().invoke(agentVersionId, toolName, safeArgs))
                .orElseGet(() -> new ToolProvider.ToolResult(false, "tool not found: " + toolName));
    }

    public List<Map<String, Object>> asOpenAiTools(String agentVersionId) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolProvider.ToolDescriptor d : listAll(agentVersionId)) {
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", d.name());
            fn.put("description", d.description());
            fn.put("parameters", d.inputSchema());
            tools.add(Map.of("type", "function", "function", fn));
        }
        return tools;
    }
}
