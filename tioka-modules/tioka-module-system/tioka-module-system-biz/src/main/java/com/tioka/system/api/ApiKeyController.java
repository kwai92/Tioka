package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.apikey.ApiKeyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** FND-06：API Key。权限在服务内校验（tenant:admin 或 workspace:manage）。 */
@RestController
@RequestMapping("/api/system/api-keys")
public class ApiKeyController {
    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @RequirePermission({})
    @PostMapping
    public ApiResponse<ApiKeyService.CreatedKey> create(@RequestBody CreateRequest body) {
        Instant expires = null;
        if (body.expiresAt() != null && !body.expiresAt().isBlank()) {
            expires = Instant.parse(body.expiresAt());
        }
        return ApiResponse.ok(apiKeys.create(
                body.serviceUserId(),
                body.name(),
                body.workspaceId(),
                body.scopes(),
                expires));
    }

    @RequirePermission({})
    @GetMapping
    public ApiResponse<List<ApiKeyService.KeyView>> list() {
        return ApiResponse.ok(apiKeys.list());
    }

    @RequirePermission({})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        apiKeys.revoke(id);
        return ApiResponse.ok(null);
    }

    public record CreateRequest(
            String serviceUserId,
            String name,
            String workspaceId,
            List<String> scopes,
            String expiresAt) {}
}
