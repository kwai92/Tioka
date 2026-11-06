package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.UserAdminService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system/users")
public class UserController {
    private final UserAdminService users;

    public UserController(UserAdminService users) {
        this.users = users;
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @PostMapping
    public ApiResponse<UserAdminService.CreatedUser> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(users.create(
                body.get("username"),
                body.get("displayName"),
                body.get("password")));
    }
}
