package com.tioka.runtime.api.approval;

import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;

import java.time.Instant;
import java.util.List;

/**
 * 审批台查询（HCI-05，施工设计 M2-04）。提交审批仍走 {@code RuntimeApi.decideApproval}。
 */
public interface ApprovalQueryApi {

    Page list(ApprovalQuery query);

    ApprovalView get(String approvalId);

    long count(Scope scope, ApprovalStatus status);

    enum Scope {
        /** 我是审批人（含按角色展开）。 */
        ASSIGNED_TO_ME,
        /** 我发起的 Turn 产生的审批。 */
        REQUESTED_BY_ME,
        /** 当前空间全部，需要 workspace:manage。 */
        WORKSPACE_ALL
    }

    record ApprovalQuery(Scope scope, ApprovalStatus status, ApprovalSubjectType subjectType, String agentId,
                         ToolRisk risk, Instant from, Instant to, String keyword, int page, int size) {}

    record Page(List<ApprovalView> items, long total, int page, int size) {}
}
