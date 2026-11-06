package com.tioka.runtime.kernel.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.kernel.approval.ApprovalDecideService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * RT-07：审批提交与查询。
 */
@RestController
@RequestMapping("/api/runtime/approvals")
@RequirePermission(Permissions.APPROVAL_DECIDE)
public class ApprovalController {
    private final ApprovalDecideService decideService;
    private final RuntimeApi runtime;

    public ApprovalController(ApprovalDecideService decideService, RuntimeApi runtime) {
        this.decideService = decideService;
        this.runtime = runtime;
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalView> get(@PathVariable String id) {
        return ApiResponse.ok(decideService.get(id));
    }

    @PostMapping("/{id}/decision")
    public ApiResponse<Void> decide(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("approved")));
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = body.get("answer") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        runtime.decideApproval(id, new RuntimeApi.ApprovalDecision(approved, answer, comment));
        return ApiResponse.ok(null);
    }
}
