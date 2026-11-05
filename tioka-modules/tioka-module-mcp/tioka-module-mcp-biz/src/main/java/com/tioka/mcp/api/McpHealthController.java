package com.tioka.mcp.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.application.McpHealthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpHealthController {
    private final McpHealthService healthService;
    private final McpAdminService adminService;

    public McpHealthController(McpHealthService healthService, McpAdminService adminService) {
        this.healthService = healthService;
        this.adminService = adminService;
    }

    @GetMapping("/servers/{id}/health")
    public ApiResponse<McpHealthService.Health> health(@PathVariable String id) {
        return ApiResponse.ok(healthService.check(id));
    }

    @PostMapping("/servers/{id}/trial")
    public ApiResponse<Map<String, Object>> trial(@PathVariable String id, @RequestBody Map<String, Object> body) {
        healthService.check(id);
        String tool = String.valueOf(body.getOrDefault("tool", "mcp_echo"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = body.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of("text", "trial");
        var result = adminService.callTool(id, tool, args);
        return ApiResponse.ok(Map.of(
                "serverId", id,
                "tool", tool,
                "ok", result.ok(),
                "result", result.content(),
                "audited", true
        ));
    }
}
