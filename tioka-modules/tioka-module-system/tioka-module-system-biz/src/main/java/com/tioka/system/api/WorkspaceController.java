package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.WorkspaceMemberService;
import com.tioka.system.application.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaceService;
    private final WorkspaceMemberService memberService;

    public WorkspaceController(WorkspaceService workspaceService, WorkspaceMemberService memberService) {
        this.workspaceService = workspaceService;
        this.memberService = memberService;
    }

    @RequirePermission("auth:me")
    @GetMapping("/mine")
    public ApiResponse<List<WorkspaceService.MineWorkspace>> mine() {
        return ApiResponse.ok(workspaceService.mine());
    }

    @RequirePermission("tenant_admin")
    @PostMapping
    public ApiResponse<WorkspaceService.Workspace> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(workspaceService.create(body.get("name")));
    }

    @RequirePermission("auth:me")
    @GetMapping("/{id}")
    public ApiResponse<WorkspaceService.Workspace> get(@PathVariable String id) {
        return ApiResponse.ok(workspaceService.require(id));
    }

    @RequirePermission("workspace:manage")
    @GetMapping("/{id}/members")
    public ApiResponse<WorkspaceMemberService.Page<WorkspaceMemberService.MemberView>> members(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(memberService.list(id, page, size));
    }

    @RequirePermission("workspace:manage")
    @PutMapping("/{id}/members/{userId}")
    public ApiResponse<Void> putMember(@PathVariable String id,
                                       @PathVariable String userId,
                                       @RequestBody Map<String, String> body) {
        memberService.put(id, userId, body.get("role"));
        return ApiResponse.ok(null);
    }

    @RequirePermission("workspace:manage")
    @DeleteMapping("/{id}/members/{userId}")
    public ApiResponse<Void> deleteMember(@PathVariable String id, @PathVariable String userId) {
        memberService.remove(id, userId);
        return ApiResponse.ok(null);
    }
}
