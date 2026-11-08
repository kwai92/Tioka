package com.tioka.mcp.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.mcp.application.McpHealthService;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
@RequirePermission(Permissions.MCP_READ)
public class McpHealthController {
    private final McpHealthService healthService;
    private final ToolGateway toolGateway;
    private final PrincipalHolder principals;

    public McpHealthController(McpHealthService healthService,
                               ToolGateway toolGateway,
                               PrincipalHolder principals) {
        this.healthService = healthService;
        this.toolGateway = toolGateway;
        this.principals = principals;
    }

    @GetMapping("/servers/{id}/health")
    public ApiResponse<McpHealthService.Health> health(@PathVariable String id) {
        return ApiResponse.ok(healthService.check(id));
    }

    /** 试调用必须经 ToolGateway（审计 / 限流 / 授权），禁止旁路 McpAdminService.callTool。 */
    @PostMapping("/servers/{id}/trial")
    @Audited(action = "mcp.server.trial", resourceType = "mcp_server", resourceIdArg = "#id")
    public ApiResponse<Map<String, Object>> trial(@PathVariable String id, @RequestBody Map<String, Object> body) {
        healthService.check(id);
        Principal principal = principals.find().orElseThrow(() ->
                new BizException(ErrorCode.UNAUTHORIZED));
        String tool = String.valueOf(body.getOrDefault("tool", "mcp_echo"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = body.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of("text", "trial");
        Map<String, Object> agentToolConfig = Map.of(
                "mcp", List.of(Map.of("serverId", id, "allowlist", List.of(tool))));
        ToolGateway.ToolCallContext ctx = ToolCallContexts.forTurn(
                principal, "mcp-trial", "mcp-trial", "mcp-trial", "mcp.trial",
                List.of(tool), agentToolConfig);
        ToolProvider.ToolResult result = toolGateway.call(ctx, tool, args);
        return ApiResponse.ok(Map.of(
                "serverId", id,
                "tool", tool,
                "ok", result.ok(),
                "result", result.content() == null ? "" : result.content(),
                "audited", true
        ));
    }
}
