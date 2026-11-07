package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface RuntimeTurnMapper {

    @Select("""
            SELECT id, session_id AS sessionId, seq, status, origin, origin_ref AS originRef,
                   client_msg_id AS clientMsgId, run_as_json AS runAsJson, options_json AS optionsJson,
                   step_count AS stepCount, recover_count AS recoverCount,
                   worker_id AS workerId, lease_version AS leaseVersion,
                   lease_until AS leaseUntil, error_code AS errorCode, error_message AS errorMessage,
                   prompt_tokens AS promptTokens, completion_tokens AS completionTokens,
                   started_at AS startedAt, ended_at AS endedAt, create_at AS createAt, update_at AS updateAt
            FROM runtime_turn WHERE id = #{id}
            """)
    RuntimeTurnDO findById(String id);

    @Select("""
            SELECT id, session_id AS sessionId, seq, status, origin, origin_ref AS originRef,
                   client_msg_id AS clientMsgId, run_as_json AS runAsJson, options_json AS optionsJson,
                   step_count AS stepCount, recover_count AS recoverCount,
                   worker_id AS workerId, lease_version AS leaseVersion,
                   lease_until AS leaseUntil, error_code AS errorCode, error_message AS errorMessage,
                   prompt_tokens AS promptTokens, completion_tokens AS completionTokens,
                   started_at AS startedAt, ended_at AS endedAt, create_at AS createAt, update_at AS updateAt
            FROM runtime_turn
            WHERE session_id = #{sessionId} AND client_msg_id = #{clientMsgId}
            """)
    RuntimeTurnDO findBySessionAndClientMsgId(@Param("sessionId") String sessionId,
                                              @Param("clientMsgId") String clientMsgId);

    @Select("""
            SELECT COUNT(*) FROM runtime_turn
            WHERE session_id = #{sessionId} AND status IN ('QUEUED', 'RUNNING')
            """)
    int countActive(String sessionId);

    @Select("""
            SELECT COALESCE(MAX(seq), 0) + 1 FROM runtime_turn WHERE session_id = #{sessionId}
            """)
    int nextSeq(String sessionId);

    @Select("SELECT 1 FROM runtime_session WHERE id = #{sessionId} FOR UPDATE")
    Integer lockSession(String sessionId);

    @Insert("""
            INSERT INTO runtime_turn (
              id, session_id, seq, status, origin, origin_ref, client_msg_id, run_as_json, options_json,
              step_count, lease_version, create_at, update_at, create_by, update_by
            ) VALUES (
              #{id}, #{sessionId}, #{seq}, #{status}, #{origin}, #{originRef}, #{clientMsgId},
              #{runAsJson}, #{optionsJson}, COALESCE(#{stepCount}, 0), COALESCE(#{leaseVersion}, 0),
              now(), now(), 'system', 'system'
            )
            """)
    void insert(RuntimeTurnDO t);

    @Select("""
            UPDATE runtime_turn
               SET status = 'RUNNING',
                   worker_id = #{workerId},
                   lease_version = lease_version + 1,
                   lease_until = #{leaseUntil},
                   started_at = COALESCE(started_at, now()),
                   update_at = now()
             WHERE id = #{turnId} AND status = 'QUEUED'
         RETURNING lease_version
            """)
    Long acquire(@Param("turnId") String turnId,
                 @Param("workerId") String workerId,
                 @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
            UPDATE runtime_turn
               SET lease_until = #{leaseUntil}, update_at = now()
             WHERE id = #{turnId} AND lease_version = #{leaseVersion} AND status = 'RUNNING'
            """)
    int renew(@Param("turnId") String turnId,
              @Param("leaseVersion") long leaseVersion,
              @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
            UPDATE runtime_turn
               SET status = #{status},
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   prompt_tokens = COALESCE(#{promptTokens}, prompt_tokens),
                   completion_tokens = COALESCE(#{completionTokens}, completion_tokens),
                   ended_at = now(),
                   lease_until = NULL,
                   update_at = now()
             WHERE id = #{turnId}
               AND lease_version = #{leaseVersion}
               AND status = 'RUNNING'
            """)
    int finish(@Param("turnId") String turnId,
               @Param("leaseVersion") long leaseVersion,
               @Param("status") String status,
               @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage,
               @Param("promptTokens") Integer promptTokens,
               @Param("completionTokens") Integer completionTokens);

    @Update("""
            UPDATE runtime_turn
               SET step_count = step_count + 1, update_at = now()
             WHERE id = #{turnId} AND lease_version = #{leaseVersion} AND status = 'RUNNING'
            """)
    int incrementStep(@Param("turnId") String turnId, @Param("leaseVersion") long leaseVersion);

    /** RT-07：RUNNING → WAITING_APPROVAL，释放租约。 */
    @Update("""
            UPDATE runtime_turn
               SET status = 'WAITING_APPROVAL',
                   lease_until = NULL,
                   worker_id = NULL,
                   update_at = now()
             WHERE id = #{turnId}
               AND lease_version = #{leaseVersion}
               AND status = 'RUNNING'
            """)
    int markWaitingApproval(@Param("turnId") String turnId, @Param("leaseVersion") long leaseVersion);

    /** RT-07：WAITING_APPROVAL → QUEUED（无 PENDING 审批后续跑）。 */
    @Update("""
            UPDATE runtime_turn
               SET status = 'QUEUED',
                   worker_id = NULL,
                   lease_until = NULL,
                   update_at = now()
             WHERE id = #{turnId} AND status = 'WAITING_APPROVAL'
            """)
    int requeueFromWaitingApproval(String turnId);

    /** RT-07：WAITING_APPROVAL → CANCELLED（interrupt）。 */
    @Update("""
            UPDATE runtime_turn
               SET status = 'CANCELLED',
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   ended_at = now(),
                   lease_until = NULL,
                   update_at = now()
             WHERE id = #{turnId} AND status = 'WAITING_APPROVAL'
            """)
    int cancelWaitingApproval(@Param("turnId") String turnId,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE runtime_turn
               SET status = 'CANCELLED',
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   ended_at = now(),
                   update_at = now()
             WHERE id = #{turnId} AND status = 'QUEUED'
            """)
    int cancelQueued(@Param("turnId") String turnId,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage);

    /**
     * FND-04e：按 run_as.userId（及可选空间）查找未终态 Turn。
     */
    @Select("""
            SELECT t.id, t.session_id AS sessionId, t.seq, t.status, t.origin,
                   t.origin_ref AS originRef, t.client_msg_id AS clientMsgId,
                   t.run_as_json AS runAsJson, t.options_json AS optionsJson,
                   t.step_count AS stepCount, t.recover_count AS recoverCount,
                   t.worker_id AS workerId, t.lease_version AS leaseVersion,
                   t.lease_until AS leaseUntil, t.error_code AS errorCode,
                   t.error_message AS errorMessage,
                   t.prompt_tokens AS promptTokens, t.completion_tokens AS completionTokens,
                   t.started_at AS startedAt, t.ended_at AS endedAt,
                   t.create_at AS createAt, t.update_at AS updateAt
              FROM runtime_turn t
              JOIN runtime_session s ON s.id = t.session_id
             WHERE t.status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_CHILD', 'INTERRUPTED')
               AND t.run_as_json::jsonb ->> 'userId' = #{userId}
               AND (CAST(#{workspaceId} AS VARCHAR) IS NULL OR s.workspace_id = CAST(#{workspaceId} AS VARCHAR))
             LIMIT 500
            """)
    List<RuntimeTurnDO> findActiveByPrincipal(@Param("userId") String userId,
                                              @Param("workspaceId") String workspaceId);

    @Select("""
            SELECT id FROM runtime_turn
             WHERE status = 'RUNNING' AND lease_until < #{now}
             ORDER BY lease_until
             LIMIT #{limit}
            """)
    List<String> findExpired(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE runtime_turn
               SET status = 'INTERRUPTED', update_at = now()
             WHERE id = #{turnId} AND status = 'RUNNING' AND lease_until < now()
            """)
    int markInterrupted(String turnId);

    @Update("""
            UPDATE runtime_turn
               SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                   ended_at = now(), lease_until = NULL, update_at = now()
             WHERE id = #{turnId} AND status = 'INTERRUPTED'
            """)
    int failInterrupted(@Param("turnId") String turnId,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage);

    /**
     * RT-06：INTERRUPTED → QUEUED 续跑；{@code lease_version + 1} 使假死复活的原执行者写不进去。
     */
    @Update("""
            UPDATE runtime_turn
               SET status = 'QUEUED',
                   recover_count = recover_count + 1,
                   lease_version = lease_version + 1,
                   worker_id = NULL,
                   lease_until = NULL,
                   update_at = now()
             WHERE id = #{turnId} AND status = 'INTERRUPTED'
            """)
    int requeueInterrupted(String turnId);

    @Select("""
            SELECT id, session_id AS sessionId, seq, status, origin, origin_ref AS originRef,
                   client_msg_id AS clientMsgId, run_as_json AS runAsJson, options_json AS optionsJson,
                   step_count AS stepCount, recover_count AS recoverCount,
                   worker_id AS workerId, lease_version AS leaseVersion,
                   lease_until AS leaseUntil, error_code AS errorCode, error_message AS errorMessage,
                   prompt_tokens AS promptTokens, completion_tokens AS completionTokens,
                   started_at AS startedAt, ended_at AS endedAt, create_at AS createAt, update_at AS updateAt
            FROM runtime_turn
            WHERE session_id = #{sessionId} AND status = 'QUEUED'
            ORDER BY seq
            LIMIT 1
            """)
    RuntimeTurnDO nextQueued(String sessionId);

    @Select("""
            SELECT id, session_id AS sessionId, seq, status, origin, origin_ref AS originRef,
                   client_msg_id AS clientMsgId, run_as_json AS runAsJson, options_json AS optionsJson,
                   step_count AS stepCount, recover_count AS recoverCount,
                   worker_id AS workerId, lease_version AS leaseVersion,
                   lease_until AS leaseUntil, error_code AS errorCode, error_message AS errorMessage,
                   prompt_tokens AS promptTokens, completion_tokens AS completionTokens,
                   started_at AS startedAt, ended_at AS endedAt, create_at AS createAt, update_at AS updateAt
            FROM runtime_turn
            WHERE session_id = #{sessionId}
              AND status IN ('RUNNING', 'WAITING_APPROVAL', 'WAITING_CHILD', 'QUEUED')
            ORDER BY CASE status
                       WHEN 'RUNNING' THEN 0
                       WHEN 'WAITING_APPROVAL' THEN 1
                       WHEN 'WAITING_CHILD' THEN 2
                       WHEN 'QUEUED' THEN 3
                       ELSE 4
                     END,
                     seq DESC
            LIMIT 1
            """)
    RuntimeTurnDO findActive(String sessionId);

    /** RT-06 QueuedSweeper：陈旧 QUEUED 且同 session 无 RUNNING、且为本 session 最小 seq。 */
    @Select("""
            SELECT t.id, t.session_id AS sessionId
              FROM runtime_turn t
             WHERE t.status = 'QUEUED'
               AND t.update_at < now() - interval '15 seconds'
               AND NOT EXISTS (
                     SELECT 1 FROM runtime_turn r
                      WHERE r.session_id = t.session_id AND r.status = 'RUNNING')
               AND t.seq = (
                     SELECT MIN(q.seq) FROM runtime_turn q
                      WHERE q.session_id = t.session_id AND q.status = 'QUEUED')
             LIMIT 100
            """)
    List<RuntimeTurnDO> findStaleQueuedForDispatch();

    @Select("SELECT pg_try_advisory_lock(hashtext('tioka.runtime.queued-sweeper'))")
    boolean tryAdvisoryLockQueuedSweeper();

    @Select("SELECT pg_advisory_unlock(hashtext('tioka.runtime.queued-sweeper'))")
    boolean unlockAdvisoryLockQueuedSweeper();
}
