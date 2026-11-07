package com.tioka.runtime.kernel.infrastructure;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.mapper.RuntimeTurnMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Repository
public class TurnRepository {
    private final RuntimeTurnMapper mapper;

    public TurnRepository(RuntimeTurnMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<RuntimeTurnDO> findById(String id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public Optional<RuntimeTurnDO> findByThreadAndClientMsgId(String threadId, String clientMsgId) {
        return Optional.ofNullable(mapper.findByThreadAndClientMsgId(threadId, clientMsgId));
    }

    public int countActive(String threadId) {
        return mapper.countActive(threadId);
    }

    @Transactional
    public int nextSeq(String threadId) {
        mapper.lockThread(threadId);
        return mapper.nextSeq(threadId);
    }

    public void insert(RuntimeTurnDO t) {
        try {
            mapper.insert(t);
        } catch (DuplicateKeyException e) {
            throw e;
        }
    }

    public OptionalLong acquire(String turnId, String workerId, Instant leaseUntil) {
        Long lv = mapper.acquire(turnId, workerId, LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC));
        return lv == null ? OptionalLong.empty() : OptionalLong.of(lv);
    }

    public int renew(String turnId, long leaseVersion, Instant leaseUntil) {
        return mapper.renew(turnId, leaseVersion, LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC));
    }

    public int finish(String turnId, long leaseVersion, TurnStatus to,
                      String errorCode, String errorMessage, Integer pt, Integer ct) {
        if (!TurnStatus.RUNNING.canTransitionTo(to)) {
            throw new IllegalStateException("illegal finish transition to " + to);
        }
        return mapper.finish(turnId, leaseVersion, to.name(), errorCode, errorMessage, pt, ct);
    }

    public int incrementStep(String turnId, long leaseVersion) {
        return mapper.incrementStep(turnId, leaseVersion);
    }

    public int markWaitingApproval(String turnId, long leaseVersion) {
        return mapper.markWaitingApproval(turnId, leaseVersion);
    }

    public int requeueFromWaitingApproval(String turnId) {
        return mapper.requeueFromWaitingApproval(turnId);
    }

    public int cancelWaitingApproval(String turnId, String errorCode, String errorMessage) {
        return mapper.cancelWaitingApproval(turnId, errorCode, errorMessage);
    }

    public int cancelQueued(String turnId) {
        return cancelQueued(turnId, null, null);
    }

    public int cancelQueued(String turnId, String errorCode, String errorMessage) {
        return mapper.cancelQueued(turnId, errorCode, errorMessage);
    }

    public List<RuntimeTurnDO> findActiveByPrincipal(String userId, String workspaceId) {
        return mapper.findActiveByPrincipal(userId, workspaceId);
    }

    public List<String> findExpired(Instant now, int limit) {
        return mapper.findExpired(LocalDateTime.ofInstant(now, ZoneOffset.UTC), limit);
    }

    public int markInterrupted(String turnId) {
        return mapper.markInterrupted(turnId);
    }

    public int failInterrupted(String turnId, String errorCode, String errorMessage) {
        return mapper.failInterrupted(turnId, errorCode, errorMessage);
    }

    public int requeueInterrupted(String turnId) {
        return mapper.requeueInterrupted(turnId);
    }

    public Optional<RuntimeTurnDO> nextQueued(String threadId) {
        return Optional.ofNullable(mapper.nextQueued(threadId));
    }

    public Optional<RuntimeTurnDO> findActive(String threadId) {
        return Optional.ofNullable(mapper.findActive(threadId));
    }

    /** HCI-07b：轨迹 Turn 列表。 */
    public List<RuntimeTurnDO> listByThread(String threadId, int offset, int limit) {
        return mapper.listByThread(threadId, offset, limit);
    }

    public List<RuntimeTurnDO> findStaleQueuedForDispatch() {
        return mapper.findStaleQueuedForDispatch();
    }

    public boolean tryAdvisoryLockQueuedSweeper() {
        return mapper.tryAdvisoryLockQueuedSweeper();
    }

    public void unlockAdvisoryLockQueuedSweeper() {
        mapper.unlockAdvisoryLockQueuedSweeper();
    }

    public void requireLeaseWrite(int affected) {
        if (affected == 0) {
            throw new BizException(ErrorCode.LEASE_LOST);
        }
    }
}
