package com.tioka.orchestration.timer;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.orchestration.receipt.OrchTimerDO;
import com.tioka.system.api.audit.Audited;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 定时器管理（ORC-06）：{@code GET/POST/DELETE /api/orch/timers}。
 */
@RestController
@RequestMapping("/api/orch/timers")
public class OrchTimerController {

    private final OrchTimerService timers;

    public OrchTimerController(OrchTimerService timers) {
        this.timers = timers;
    }

    @GetMapping
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<List<OrchTimerDO>> list(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(timers.list(workspaceId, status));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<OrchTimerDO> get(@PathVariable String id) {
        return ApiResponse.ok(timers.get(id));
    }

    @PostMapping
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.timer.create", resourceType = "orch_timer")
    public ApiResponse<OrchTimerDO> create(@RequestBody Map<String, Object> body) {
        String kind = str(body.get("kind"));
        String workspaceId = str(body.get("workspaceId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> target = body.get("target") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        if ("CRON".equalsIgnoreCase(kind)) {
            return ApiResponse.ok(timers.createCron(
                    workspaceId,
                    str(body.get("ruleId")),
                    str(body.get("cron")),
                    str(body.get("tz")),
                    str(body.get("misfire")),
                    target));
        }
        LocalDateTime at = parseAt(body.get("at"));
        return ApiResponse.ok(timers.createLater(
                workspaceId,
                str(body.get("workItemId")),
                str(body.get("timerKey")),
                at,
                target));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.timer.cancel", resourceType = "orch_timer", resourceIdArg = "#id")
    public ApiResponse<Void> cancel(@PathVariable String id) {
        timers.cancel(id);
        return ApiResponse.ok(null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static LocalDateTime parseAt(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("at 必填");
        }
        String s = String.valueOf(o);
        try {
            return OffsetDateTime.parse(s).toLocalDateTime();
        } catch (Exception ignored) {
            return LocalDateTime.parse(s);
        }
    }
}
