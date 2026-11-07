package com.tioka.server.catalog;

import com.tioka.agent.api.AgentCatalogApi;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.mcp.api.McpServerQueryApi;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
@RequirePermission(Permissions.AGENT_READ)
public class AssetCatalogController {
    private final AgentCatalogApi agentCatalogApi;
    private final McpServerQueryApi mcpServerQueryApi;

    public AssetCatalogController(AgentCatalogApi agentCatalogApi, McpServerQueryApi mcpServerQueryApi) {
        this.agentCatalogApi = agentCatalogApi;
        this.mcpServerQueryApi = mcpServerQueryApi;
    }

    @GetMapping
    public ApiResponse<List<Map<String, String>>> search(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.toLowerCase();
        List<Map<String, String>> out = new ArrayList<>();
        agentCatalogApi.listSummaries().stream()
                .filter(a -> a.name() != null && a.name().toLowerCase().contains(query))
                .forEach(a -> out.add(Map.of("type", "agent", "id", a.id(), "name", a.name())));
        mcpServerQueryApi.listSummaries().stream()
                .filter(s -> s.name() != null && s.name().toLowerCase().contains(query))
                .forEach(s -> out.add(Map.of("type", "mcp", "id", s.id(), "name", s.name())));
        return ApiResponse.ok(out);
    }
}
