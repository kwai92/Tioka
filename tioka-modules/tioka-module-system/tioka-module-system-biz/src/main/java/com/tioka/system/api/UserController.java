package com.tioka.system.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.UserAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system/users")
public class UserController {
    private final UserAdminService users;

    public UserController(UserAdminService users) {
        this.users = users;
    }

    /** HCI-03 */
    @RequirePermission(Permissions.TENANT_ADMIN)
    @GetMapping
    public ApiResponse<UserAdminService.Page<UserAdminService.UserView>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(users.list(q, page, size));
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @PostMapping
    @Audited(action = "user.create", resourceType = "user")
    public ApiResponse<UserAdminService.CreatedUser> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(users.create(
                body.get("username"),
                body.get("displayName"),
                body.get("password")));
    }

    /** FND-04e */
    @RequirePermission(Permissions.TENANT_ADMIN)
    @PutMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable String id) {
        users.disable(id);
        return ApiResponse.ok(null);
    }

    /** FND-04e */
    @RequirePermission(Permissions.TENANT_ADMIN)
    @PutMapping("/{id}/enable")
    public ApiResponse<Void> enable(@PathVariable String id) {
        users.enable(id);
        return ApiResponse.ok(null);
    }

    /** HCI-03 */
    @RequirePermission(Permissions.TENANT_ADMIN)
    @PutMapping("/{id}/reset-password")
    public ApiResponse<UserAdminService.ResetPasswordResult> resetPassword(@PathVariable String id) {
        return ApiResponse.ok(users.resetPassword(id));
    }
}
