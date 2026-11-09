package com.tioka.runtime.api.approval;

import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.spi.ChangeSet;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审批台查询（HCI-05，施工设计 M2-04）。提交审批仍走 {@code RuntimeApi.decideApproval}。
 */
public interface ApprovalQueryApi {

    Page list(ApprovalQuery query);

    ApprovalView get(String approvalId);

    long count(Scope scope, ApprovalStatus status);

    /** 审批台详情：按审批 ID 查关联变更单及当前状态（TRU-06b / PK-25）；无关联变更单时为空。 */
    Optional<ChangeSetDetail> changeSet(String approvalId);

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

    /**
     * 变更单详情（TRU-06b）。状态口径（M2-05 第 111 行）：
     * PROPOSED（prepare 后）→ APPROVED → APPLIED（commit 后）/ STALE（基线失效）/ FAILED；
     * {@code estimated=true} 表示预估态（推断变更单，非事务型工具）。
     *
     * @param rows 「对象 | 字段 | 原值 | 新值」行（UPDATE 只列变化字段，前 10 条）
     */
    record ChangeSetDetail(
            String approvalId,
            String preparedId,
            String toolName,
            String changeStatus,
            String reversibility,
            boolean estimated,
            List<Map<String, Object>> rows,
            ChangeSet changeSet) {}
}
