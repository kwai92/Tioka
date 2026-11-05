
package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.system.application.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/system/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    public ApiResponse<WorkspaceService.Workspace> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(workspaceService.create(body.get("name"), 1L));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkspaceService.Workspace> get(@PathVariable String id) {
        return ApiResponse.ok(workspaceService.require(id));
    }
}
