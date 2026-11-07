package com.tioka.mcp.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/servers/{id}/tools")
    public ApiResponse<List<McpStore.Tool>> serverTools(@PathVariable String id) {
        return ApiResponse.ok(service.toolsOf(id));
    }

    @PostMapping("/servers")
    @Audited(action = "mcp.server.register", resourceType = "mcp_server")
    public ApiResponse<McpStore.McpServer> register(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.register(body.get("name"), body.get("baseUrl"), body.get("bearerToken")));
    }

    @PostMapping("/servers/{id}/refresh-tools")
    @Audited(action = "mcp.server.refresh_tools", resourceType = "mcp_server", resourceIdArg = "#id")
    public ApiResponse<List<McpStore.Tool>> refresh(@PathVariable String id) {
        return ApiResponse.ok(service.refreshTools(id));
    }

    /** FE-01：编辑 mcp_tool.risk（V114 四态）。 */
    @PatchMapping("/servers/{serverId}/tools/{toolName}")
    @Audited(action = "mcp.tool.risk.update", resourceType = "mcp_tool", resourceIdArg = "#toolName")
    public ApiResponse<McpStore.Tool> updateToolRisk(@PathVariable String serverId,
                                                     @PathVariable String toolName,
                                                     @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.updateToolRisk(serverId, toolName,
                body == null ? null : body.get("risk")));
    }
}
