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
        return resolve(agentVersionId, Map.of(), toolName);
    }

    public Optional<ResolvedTool> resolve(String agentVersionId, Map<String, Object> agentToolConfig,
                                          String toolName) {
        if (agentVersionId == null || agentVersionId.isBlank() || toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> cfg = agentToolConfig == null ? Map.of() : agentToolConfig;
        for (ToolProvider p : providers) {
            for (ToolProvider.ToolDescriptor d : p.listTools(agentVersionId, cfg)) {
                if (toolName.equals(d.name())) {
                    return Optional.of(new ResolvedTool(p, d));
                }
            }
        }
        return Optional.empty();
    }

    /** 工具所属 MCP serverId（DAT-02 SinkGuard sink_ref 用）；非 MCP 工具为空。 */
    public Optional<String> mcpServerIdOf(String agentVersionId, String toolName,
                                          Map<String, Object> agentToolConfig) {
        return resolve(agentVersionId, agentToolConfig, toolName)
                .flatMap(r -> r.provider().mcpServerId(agentVersionId, toolName, agentToolConfig));
    }

    public List<ToolProvider.ToolDescriptor> listAll(String agentVersionId) {
        return listAll(agentVersionId, Map.of());
    }

    public List<ToolProvider.ToolDescriptor> listAll(String agentVersionId,
                                                     Map<String, Object> agentToolConfig) {
        Map<String, Object> cfg = agentToolConfig == null ? Map.of() : agentToolConfig;
        List<ToolProvider.ToolDescriptor> all = new ArrayList<>();
        for (ToolProvider p : providers) {
            all.addAll(p.listTools(agentVersionId, cfg));
        }
        return all;
    }

    public ToolProvider.ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> args) {
        return invoke(agentVersionId, Map.of(), toolName, args);
    }

    public ToolProvider.ToolResult invoke(String agentVersionId, Map<String, Object> agentToolConfig,
                                          String toolName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Map<String, Object> cfg = agentToolConfig == null ? Map.of() : agentToolConfig;
        return resolve(agentVersionId, cfg, toolName)
                .map(r -> r.provider().invoke(agentVersionId, toolName, safeArgs, cfg))
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
