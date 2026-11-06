
package com.tioka.mcp.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/mcp")
@RequirePermission(Permissions.MCP_WRITE)
public class McpAdminController {
    private final McpAdminService service;

    public McpAdminController(McpAdminService service) {
        this.service = service;
    }

    @GetMapping("/servers")
    public ApiResponse<List<McpStore.McpServer>> listServers() {
        return ApiResponse.ok(service.listServers());
    }

    @GetMapping("/grants")
    public ApiResponse<List<McpStore.Grant>> listGrants() {
        return ApiResponse.ok(service.listGrants());
    }

    @GetMapping("/servers/{id}/tools")
    public ApiResponse<List<McpStore.Tool>> serverTools(@PathVariable String id) {
        return ApiResponse.ok(service.toolsOf(id));
    }

    @PostMapping("/servers")
    public ApiResponse<McpStore.McpServer> register(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.register(body.get("name"), body.get("baseUrl"), body.get("bearerToken")));
    }

    @PostMapping("/servers/{id}/refresh-tools")
    public ApiResponse<List<McpStore.Tool>> refresh(@PathVariable String id) {
        return ApiResponse.ok(service.refreshTools(id));
    }

    @PostMapping("/grants")
    public ApiResponse<McpStore.Grant> grant(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> allow = (List<String>) body.getOrDefault("allowlist", List.of());
        return ApiResponse.ok(service.grant(String.valueOf(body.get("agentId")), String.valueOf(body.get("serverId")), allow));
    }

    @GetMapping("/agents/{agentId}/tools")
    public ApiResponse<List<McpStore.Tool>> agentTools(@PathVariable String agentId) {
        return ApiResponse.ok(service.toolsForAgent(agentId));
    }
}
