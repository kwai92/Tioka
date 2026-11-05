package com.tioka.runtime.api.approval;

import com.tioka.runtime.api.kernel.ApprovalKind;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 审批视图（RT-07、HCI-05，施工设计 M1-02、M2-04）。审批台、IM 卡片与各 subject 处理器共用。
 */
public record ApprovalView(
        String id,
        ApprovalSubjectType subjectType,
        String subjectId,
        ApprovalKind kind,
        String title,
        String summary,
        String toolName,
        Map<String, Object> args,
        Map<String, Object> card,
        ToolRisk risk,
        ApprovalStatus status,
        String workspaceId,
        String requestedBy,
        List<String> assigneeUserIds,
        List<String> assigneeRoles,
        String decidedBy,
        Instant decidedAt,
        Map<String, Object> answer,
        String comment,
        Instant expiresAt,
        Instant createdAt,
        SubjectLink link) {

    /** 跳转到来源：会话、编排 Run 或 Flow 运行。 */
    public record SubjectLink(String type, String id, String title, String url) {}
}
