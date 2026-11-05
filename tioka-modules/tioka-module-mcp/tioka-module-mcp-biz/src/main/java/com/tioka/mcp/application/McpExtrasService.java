package com.tioka.mcp.application;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class McpExtrasService {
    public List<Map<String, Object>> resources(String serverId) {
        return List.of(Map.of("uri", "mock://resource/" + serverId, "name", "demo"));
    }

    public List<Map<String, Object>> prompts(String serverId) {
        return List.of(Map.of("name", "demo_prompt", "description", "mock"));
    }
}
