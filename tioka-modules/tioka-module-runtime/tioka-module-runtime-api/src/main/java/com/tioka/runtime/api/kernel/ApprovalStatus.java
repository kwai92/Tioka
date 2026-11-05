package com.tioka.runtime.api.kernel;

/** 审批状态；只能从 PENDING 转到其余任一终态。 */
public enum ApprovalStatus {
    PENDING, APPROVED, REJECTED, EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
