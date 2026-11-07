package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.authz.GroupAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/groups")
public class GroupController {
    private final GroupAdminService groups;

    public GroupController(GroupAdminService groups) {
        this.groups = groups;
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @GetMapping
    public ApiResponse<List<GroupAdminService.GroupView>> list() {
        return ApiResponse.ok(groups.list());
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @PostMapping
    public ApiResponse<GroupAdminService.GroupView> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(groups.create(body.get("name")));
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @PutMapping("/{id}/members")
    public ApiResponse<GroupAdminService.GroupView> members(@PathVariable String id,
                                                            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> memberIds = (List<String>) body.getOrDefault("memberIds", List.of());
        return ApiResponse.ok(groups.replaceMembers(id, memberIds));
    }
}
