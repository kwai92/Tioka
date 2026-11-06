package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.QuotaService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GOV-03：{@code /api/system/quotas}（取代旧 {@code /api/system/quota/workspaces/...}）。
 */
@RestController
@RequestMapping("/api/system/quotas")
public class QuotaController {
    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping
    @RequirePermission(Permissions.QUOTA_READ)
    public ApiResponse<List<QuotaService.QuotaView>> list(
            @RequestParam String scopeType,
            @RequestParam String scopeId) {
        return ApiResponse.ok(quotaService.list(scopeType, scopeId));
    }

    @PutMapping("/{scopeType}/{scopeId}/{metric}")
    @RequirePermission(Permissions.QUOTA_ADMIN)
    public ApiResponse<Map<String, Object>> put(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable String metric,
            @RequestBody Map<String, Object> body) {
        Long limit = null;
        if (body != null && body.containsKey("limit") && body.get("limit") != null) {
            limit = Long.parseLong(String.valueOf(body.get("limit")));
        }
        quotaService.putLimit(scopeType, scopeId, metric, limit);
        return ApiResponse.ok(Map.of("ok", true));
    }
}
