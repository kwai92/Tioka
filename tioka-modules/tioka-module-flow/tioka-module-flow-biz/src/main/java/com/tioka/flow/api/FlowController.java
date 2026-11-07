package com.tioka.flow.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.flow.application.FlowService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/flows")
@RequirePermission(Permissions.FLOW_WRITE)
public class FlowController {
    private final FlowService flowService;

    public FlowController(FlowService flowService) {
        this.flowService = flowService;
    }

    @GetMapping("/node-types")
    public ApiResponse<java.util.List<String>> nodeTypes() {
        return ApiResponse.ok(flowService.nodeTypes());
    }

    @PostMapping("/drafts")
    @Audited(action = "flow.draft.save", resourceType = "flow")
    public ApiResponse<FlowService.FlowDraft> save(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) body.get("graph");
        return ApiResponse.ok(flowService.saveDraft(String.valueOf(body.get("name")), graph));
    }

    @PostMapping("/drafts/{id}/publish")
    @Audited(action = "flow.draft.publish", resourceType = "flow", resourceIdArg = "#id")
    public ApiResponse<FlowService.FlowVersion> publish(@PathVariable String id) {
        return ApiResponse.ok(flowService.publish(id));
    }

    @PostMapping("/versions/{id}/run")
    @Audited(action = "flow.version.run", resourceType = "flow_version", resourceIdArg = "#id")
    public ApiResponse<FlowService.FlowRun> run(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(flowService.run(id, body == null ? Map.of() : body));
    }
}
