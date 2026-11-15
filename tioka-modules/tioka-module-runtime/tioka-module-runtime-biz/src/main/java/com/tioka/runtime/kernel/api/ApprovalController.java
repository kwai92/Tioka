package com.tioka.runtime.kernel.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.api.approval.ApprovalQueryApi;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.kernel.approval.HumanTaskSubmitService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * RT-07 审批提交 + HCI-05b 审批台查询。决定路径不变；本包仅加查询端点。
 */
@RestController
@RequestMapping("/api/runtime/approvals")
@RequirePermission(Permissions.APPROVAL_DECIDE)
public class ApprovalController {
    private final RuntimeApi runtime;
    private final ApprovalQueryApi approvalQuery;
    private final HumanTaskSubmitService humanTasks;

    public ApprovalController(RuntimeApi runtime, ApprovalQueryApi approvalQuery,
                              HumanTaskSubmitService humanTasks) {
        this.runtime = runtime;
        this.approvalQuery = approvalQuery;
        this.humanTasks = humanTasks;
    }

    @GetMapping
    public ApiResponse<ApprovalQueryApi.Page> list(
            @RequestParam(defaultValue = "ASSIGNED_TO_ME") ApprovalQueryApi.Scope scope,
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(required = false) ApprovalSubjectType subjectType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) ToolRisk risk,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(approvalQuery.list(new ApprovalQueryApi.ApprovalQuery(
                scope, status, subjectType, agentId, risk, from, to, keyword, page, size)));
    }

    @GetMapping("/count")
    public ApiResponse<Long> count(
            @RequestParam(defaultValue = "ASSIGNED_TO_ME") ApprovalQueryApi.Scope scope,
            @RequestParam(required = false) ApprovalStatus status) {
        return ApiResponse.ok(approvalQuery.count(scope, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalView> get(@PathVariable String id) {
        return ApiResponse.ok(approvalQuery.get(id));
    }

    /** TRU-06b：审批台详情——有变更单时以变更单替代参数 JSON。 */
    @GetMapping("/{id}/changeset")
    public ApiResponse<ApprovalQueryApi.ChangeSetDetail> changeSet(@PathVariable String id) {
        return ApiResponse.ok(approvalQuery.changeSet(id).orElse(null));
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

    /** SCH-09 / ADR-066 §7.4：提交人工补录数据；原 Turn 续跑，经第 3〜8 步校验后写 data item。 */
    @PostMapping("/human-tasks/{id}/submit")
    public ApiResponse<Void> submitHumanTask(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = body.get("answer") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : body;
        humanTasks.submit(id, answer);
        return ApiResponse.ok(null);
    }
}
