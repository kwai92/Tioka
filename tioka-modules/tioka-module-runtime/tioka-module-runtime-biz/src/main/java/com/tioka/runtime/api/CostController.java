package com.tioka.runtime.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.runtime.application.CostTimelineService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/runtime/cost")
@RequirePermission(Permissions.QUOTA_READ)
public class CostController {
    private final CostTimelineService costTimelineService;

    public CostController(CostTimelineService costTimelineService) {
        this.costTimelineService = costTimelineService;
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<List<CostTimelineService.CostEvent>> timeline(@PathVariable String id) {
        return ApiResponse.ok(costTimelineService.timeline(id));
    }
}
