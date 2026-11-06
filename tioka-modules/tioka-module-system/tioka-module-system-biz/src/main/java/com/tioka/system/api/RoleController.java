package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.authz.RoleAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class RoleController {
    private final RoleAdminService roles;

    public RoleController(RoleAdminService roles) {
        this.roles = roles;
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @GetMapping("/roles")
    public ApiResponse<List<RoleAdminService.RoleView>> listRoles() {
        return ApiResponse.ok(roles.list());
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @PostMapping("/roles")
    public ApiResponse<RoleAdminService.RoleView> createRole(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.getOrDefault("permissions", List.of());
        return ApiResponse.ok(roles.create(
                str(body.get("code")),
                str(body.get("name")),
                str(body.get("scope")),
                permissions));
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @PutMapping("/roles/{code}")
    public ApiResponse<RoleAdminService.RoleView> updateRole(@PathVariable String code,
                                                             @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.getOrDefault("permissions", List.of());
        return ApiResponse.ok(roles.update(code, str(body.get("name")), permissions));
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @DeleteMapping("/roles/{code}")
    public ApiResponse<Void> deleteRole(@PathVariable String code) {
        roles.delete(code);
        return ApiResponse.ok(null);
    }

    @RequirePermission({})
    @GetMapping("/permissions")
    public ApiResponse<List<String>> permissions() {
        return ApiResponse.ok(roles.permissionCatalog());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
