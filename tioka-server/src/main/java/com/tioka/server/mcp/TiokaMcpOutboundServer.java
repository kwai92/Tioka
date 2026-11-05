package com.tioka.server.mcp;

import com.tioka.flow.application.FlowPublicationService;
import com.tioka.knowledge.application.KnowledgeService;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import com.tioka.framework.error.ApiResponse;

@Service
@RestController
@RequestMapping("/api/mcp/outbound")
public class TiokaMcpOutboundServer {
    private final KnowledgeService knowledgeService;
    private final FlowPublicationService flowPublicationService;

    public TiokaMcpOutboundServer(KnowledgeService knowledgeService, FlowPublicationService flowPublicationService) {
        this.knowledgeService = knowledgeService;
        this.flowPublicationService = flowPublicationService;
    }

    @GetMapping("/tools")
    public ApiResponse<List<Map<String, Object>>> listTools() {
        return ApiResponse.ok(List.of(
                Map.of("name", "tioka.kb.search", "description", "KB search"),
                Map.of("name", "tioka.flow.run", "description", "Run published flow")
        ));
    }

    @PostMapping("/call")
    public ApiResponse<Object> call(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) body.getOrDefault("arguments", Map.of());
        if ("tioka.kb.search".equals(name)) {
            return ApiResponse.ok(knowledgeService.search(String.valueOf(args.get("kbId")), String.valueOf(args.get("query")), 3));
        }
        if ("tioka.flow.run".equals(name)) {
            return ApiResponse.ok(flowPublicationService.invokeInternalApi(String.valueOf(args.get("versionId")), args));
        }
        return ApiResponse.ok(Map.of("error", "unknown"));
    }
}
