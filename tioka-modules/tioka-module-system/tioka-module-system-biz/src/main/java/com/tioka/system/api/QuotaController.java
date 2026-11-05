package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.system.application.QuotaService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/system/quota")
public class QuotaController {
    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @PostMapping("/workspaces/{id}/limits")
    public ApiResponse<QuotaService.Limits> limits(@PathVariable String id, @RequestBody Map<String, Object> body) {
        int tokenLimit = Integer.parseInt(String.valueOf(body.getOrDefault("tokenLimit", 1_000_000)));
        int concurrentLimit = Integer.parseInt(String.valueOf(body.getOrDefault("concurrentLimit", 32)));
        return ApiResponse.ok(quotaService.setLimits(id, tokenLimit, concurrentLimit));
    }

    @PostMapping("/workspaces/{id}/consume")
    public ApiResponse<Map<String, Object>> consume(@PathVariable String id, @RequestBody Map<String, Object> body) {
        int tokens = Integer.parseInt(String.valueOf(body.getOrDefault("tokens", 0)));
        quotaService.consumeTokens(id, tokens);
        return ApiResponse.ok(Map.of("ok", true));
    }
}
