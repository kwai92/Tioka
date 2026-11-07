package com.tioka.server.mcp;

import com.tioka.system.api.audit.Audited;

import com.tioka.flow.api.FlowInvokeApi;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.knowledge.api.KnowledgeSearchApi;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Service
@RestController
@RequestMapping("/api/mcp/outbound")
@RequirePermission(Permissions.MCP_READ)
public class TiokaMcpOutboundServer {
    private final KnowledgeSearchApi knowledgeSearchApi;
    private final FlowInvokeApi flowInvokeApi;

    public TiokaMcpOutboundServer(KnowledgeSearchApi knowledgeSearchApi, FlowInvokeApi flowInvokeApi) {
        this.knowledgeSearchApi = knowledgeSearchApi;
        this.flowInvokeApi = flowInvokeApi;
    }

    @GetMapping("/tools")
    public ApiResponse<List<Map<String, Object>>> listTools() {
        return ApiResponse.ok(List.of(
                Map.of("name", "tioka.kb.search", "description", "KB search"),
                Map.of("name", "tioka.flow.run", "description", "Run published flow")
        ));
    }

    @PostMapping("/call")
    @Audited(action = "mcp.outbound.call", resourceType = "mcp_tool")
    public ApiResponse<Object> call(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) body.getOrDefault("arguments", Map.of());
        if ("tioka.kb.search".equals(name)) {
            return ApiResponse.ok(knowledgeSearchApi.searchKnowledge(
                    String.valueOf(args.get("kbId")), String.valueOf(args.get("query")), 3));
        }
        if ("tioka.flow.run".equals(name)) {
            return ApiResponse.ok(flowInvokeApi.invokeInternalApi(String.valueOf(args.get("versionId")), args));
        }
        return ApiResponse.ok(Map.of("error", "unknown"));
    }
}
