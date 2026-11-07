package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuntimeTurnMapper {

        RuntimeTurnDO findById(String id);

        RuntimeTurnDO findByThreadAndClientMsgId(@Param("threadId") String threadId,
                                              @Param("clientMsgId") String clientMsgId);

        int countActive(String threadId);

        int nextSeq(String threadId);

        Integer lockThread(String threadId);

        void insert(RuntimeTurnDO t);

        Long acquire(@Param("turnId") String turnId,
                 @Param("workerId") String workerId,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

        int renew(@Param("turnId") String turnId,
              @Param("leaseVersion") long leaseVersion,
              @Param("leaseUntil") LocalDateTime leaseUntil);

        int finish(@Param("turnId") String turnId,
               @Param("leaseVersion") long leaseVersion,
               @Param("status") String status,
               @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage,
               @Param("promptTokens") Integer promptTokens,
               @Param("completionTokens") Integer completionTokens);

        int incrementStep(@Param("turnId") String turnId, @Param("leaseVersion") long leaseVersion);

    /** RT-07：RUNNING → WAITING_APPROVAL，释放租约。 */
        int markWaitingApproval(@Param("turnId") String turnId, @Param("leaseVersion") long leaseVersion);

    /** RT-07：WAITING_APPROVAL → QUEUED（无 PENDING 审批后续跑）。 */
        int requeueFromWaitingApproval(String turnId);

    /** RT-07：WAITING_APPROVAL → CANCELLED（interrupt）。 */
        int cancelWaitingApproval(@Param("turnId") String turnId,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

        int cancelQueued(@Param("turnId") String turnId,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage);

    /**
     * FND-04e：按 run_as.userId（及可选空间）查找未终态 Turn。
     */
        List<RuntimeTurnDO> findActiveByPrincipal(@Param("userId") String userId,
                                              @Param("workspaceId") String workspaceId);

        List<String> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

        int markInterrupted(String turnId);

        int failInterrupted(@Param("turnId") String turnId,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage);

    /**
     * RT-06：INTERRUPTED → QUEUED 续跑；{@code lease_version + 1} 使假死复活的原执行者写不进去。
     */
        int requeueInterrupted(String turnId);

        RuntimeTurnDO nextQueued(String threadId);

        RuntimeTurnDO findActive(String threadId);

    /** RT-06 QueuedSweeper：陈旧 QUEUED 且同 thread 无 RUNNING、且为本 thread 最小 seq。 */
        List<RuntimeTurnDO> findStaleQueuedForDispatch();

        boolean tryAdvisoryLockQueuedSweeper();

        boolean unlockAdvisoryLockQueuedSweeper();

    /** HCI-07b：按 thread 分页列出 Turn（seq 升序）。 */
        List<RuntimeTurnDO> listByThread(@Param("threadId") String threadId,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);
}
