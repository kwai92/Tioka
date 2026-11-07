package com.tioka.runtime.kernel.approval;

import com.tioka.runtime.api.approval.ApprovalSubjectHandler;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.springframework.stereotype.Component;

/**
 * Turn 主体审批续跑：无 PENDING 时 WAITING_APPROVAL → QUEUED 并 XADD（M1-02）。
 */
@Component
public class TurnApprovalHandler implements ApprovalSubjectHandler {
    private final ApprovalRepository approvals;
    private final TurnRepository turns;
    private final TurnDispatchQueue dispatchQueue;

    public TurnApprovalHandler(ApprovalRepository approvals,
                               TurnRepository turns,
                               TurnDispatchQueue dispatchQueue) {
        this.approvals = approvals;
        this.turns = turns;
        this.dispatchQueue = dispatchQueue;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectType.TURN.wire();
    }

    @Override
    public void onDecided(ApprovalView approval) {
        if (approval == null || approval.subjectId() == null) {
            return;
        }
        String turnId = approval.subjectId();
        if (approvals.countPending(ApprovalSubjectType.TURN.wire(), turnId) > 0) {
            return;
        }
        int n = turns.requeueFromWaitingApproval(turnId);
        if (n == 0) {
            return;
        }
        turns.findById(turnId).ifPresent(t -> dispatchQueue.offer(turnId, t.getSessionId()));
    }
}
