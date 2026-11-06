package com.tioka.model.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/model")
@RequirePermission(Permissions.MODEL_READ)
public class ModelRouterController {
    private final ModelRouterApi router;

    public ModelRouterController(ModelRouterApi router) {
        this.router = router;
    }

    @PostMapping("/router/select")
    public ApiResponse<ModelRouterApi.ModelSelection> select(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(router.select(
                body.get("sessionId"),
                body.get("routingPlanId"),
                body.get("userText")));
    }

    @PostMapping("/router/failover")
    public ApiResponse<ModelRouterApi.ModelSelection> failover(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(router.failover(
                body.get("sessionId"),
                body.get("routingPlanId"),
                body.get("reason")));
    }

    @GetMapping("/sessions/{id}/route")
    public ApiResponse<ModelRouterApi.RouteView> route(@PathVariable String id) {
        return ApiResponse.ok(router.lastRoute(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "尚无选模记录")));
    }
}
