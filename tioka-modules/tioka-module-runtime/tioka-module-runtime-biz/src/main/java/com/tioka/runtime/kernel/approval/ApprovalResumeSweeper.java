package com.tioka.runtime.kernel.approval;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.infrastructure.mapper.RuntimeApprovalMapper;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 兜底：WAITING_APPROVAL 且无 PENDING 审批 → QUEUED（提交后崩溃未调 handler，M1-02）。
 */
@Component
public class ApprovalResumeSweeper {
    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeSweeper.class);

    private final ApprovalRepository approvals;
    private final TurnRepository turns;
    private final TurnDispatchQueue dispatchQueue;
    private final PrincipalHolder principals;

    public ApprovalResumeSweeper(ApprovalRepository approvals,
                                 TurnRepository turns,
                                 TurnDispatchQueue dispatchQueue,
                                 PrincipalHolder principals) {
        this.approvals = approvals;
        this.turns = turns;
        this.dispatchQueue = dispatchQueue;
        this.principals = principals;
    }

    @Scheduled(fixedDelayString = "${tioka.runtime.approval-resume-sweeper-interval-ms:30000}")
    public void sweep() {
        principals.runAs(SystemPrincipals.system(), () -> {
            for (RuntimeApprovalMapper.WaitingTurnRow row : approvals.findWaitingWithoutPending()) {
                if (approvals.countPending(ApprovalSubjectType.TURN.wire(), row.id()) > 0) {
                    continue;
                }
                int n = turns.requeueFromWaitingApproval(row.id());
                if (n > 0) {
                    dispatchQueue.offer(row.id(), row.sessionId());
                    log.debug("ApprovalResumeSweeper requeued turn {}", row.id());
                }
            }
            return null;
        });
    }
}
