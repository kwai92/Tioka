package com.tioka.runtime.infrastructure.persistence;

/**
 * runtime_approval_assignee 行（V143）：展开后的审批人，供「待我审批」查询。
 */
public class RuntimeApprovalAssigneeDO {
    private String approvalId;
    private String userId;

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
