package com.tioka.orchestration.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.orchestration.application.LaterTriggerService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/orch/later")
@RequirePermission(Permissions.ORCH_WRITE)
public class LaterController {
    private final LaterTriggerService laterTriggerService;

    public LaterController(LaterTriggerService laterTriggerService) {
        this.laterTriggerService = laterTriggerService;
    }

    @PostMapping
    @Audited(action = "orch.later.schedule", resourceType = "orch_later")
    public ApiResponse<String> schedule(@RequestBody Map<String, Object> body) {
        long delay = Long.parseLong(String.valueOf(body.getOrDefault("delayMs", 0)));
        return ApiResponse.ok(laterTriggerService.schedule(String.valueOf(body.get("eventType")), delay, body));
    }
}
