package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.apikey.ServiceAccountService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** FND-06：服务账号。 */
@RestController
@RequestMapping("/api/system/service-accounts")
public class ServiceAccountController {
    private final ServiceAccountService serviceAccounts;

    public ServiceAccountController(ServiceAccountService serviceAccounts) {
        this.serviceAccounts = serviceAccounts;
    }

    @RequirePermission(Permissions.TENANT_ADMIN)
    @PostMapping
    public ApiResponse<ServiceAccountService.Created> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(serviceAccounts.create(
                body.get("name"),
                body.get("workspaceId"),
                body.get("role")));
    }
}
