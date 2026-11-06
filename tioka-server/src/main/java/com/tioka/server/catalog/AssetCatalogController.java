package com.tioka.server.catalog;

import com.tioka.agent.application.AgentService;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.mcp.application.McpAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
@RequirePermission(Permissions.AGENT_READ)
public class AssetCatalogController {
    private final AgentService agentService;
    private final McpAdminService mcpAdminService;

    public AssetCatalogController(AgentService agentService, McpAdminService mcpAdminService) {
        this.agentService = agentService;
        this.mcpAdminService = mcpAdminService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, String>>> search(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.toLowerCase();
        List<Map<String, String>> out = new ArrayList<>();
        agentService.list().stream().filter(a -> a.name().toLowerCase().contains(query))
                .forEach(a -> out.add(Map.of("type", "agent", "id", a.id(), "name", a.name())));
        mcpAdminService.listServers().stream().filter(s -> s.name().toLowerCase().contains(query))
                .forEach(s -> out.add(Map.of("type", "mcp", "id", s.id(), "name", s.name())));
        return ApiResponse.ok(out);
    }
}
