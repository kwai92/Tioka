package com.tioka.model.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model")
@RequirePermission(Permissions.MODEL_READ)
public class ModelRouterController {
    private final ModelRouterApi router;

    public ModelRouterController(ModelRouterApi router) {
        this.router = router;
    }

    @PostMapping("/router/failover")
    public ApiResponse<ModelRouterApi.ModelSelection> failover(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(router.failover(
                body.get("threadId"),
                body.get("turnId"),
                body.get("routingPlanId"),
                body.get("reason")));
    }

    /** MH-01：调试查询轮次冻结（取代 /router/select 与 sessions/{id}/route）。 */
    @GetMapping("/freezes")
    public ApiResponse<List<ModelRouterApi.FreezeView>> freezes(@RequestParam String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "threadId 必填");
        }
        return ApiResponse.ok(router.listFreezes(threadId));
    }
}
