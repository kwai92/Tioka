package com.tioka.runtime.application;

import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class EchoToolProvider implements ToolProvider {
    @Override
    public String name() { return "builtin"; }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(ToolDescriptor.builder()
                .name("echo")
                .description("Echo back the given text")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .build());
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        if (!"echo".equals(toolName)) {
            return new ToolResult(false, "unknown tool");
        }
        Object text = arguments.getOrDefault("text", "");
        return new ToolResult(true, String.valueOf(text));
    }
}
