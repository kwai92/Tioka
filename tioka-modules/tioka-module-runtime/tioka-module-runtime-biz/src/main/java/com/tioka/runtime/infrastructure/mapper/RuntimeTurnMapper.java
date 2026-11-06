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
                   step_count AS stepCount, worker_id AS workerId, lease_version AS leaseVersion,
                   lease_until AS leaseUntil, error_code AS errorCode, error_message AS errorMessage,
                   prompt_tokens AS promptTokens, completion_tokens AS completionTokens,
                   started_at AS startedAt, ended_at AS endedAt, create_at AS createAt, update_at AS updateAt
            FROM runtime_turn WHERE id = #{id}
            """)
    RuntimeTurnDO findById(String id);

    @Select("""
            SELECT id, session_id AS sessionId, seq, status, origin, origin_ref AS originRef,
                   client_msg_id AS clientMsgId, run_as_json AS runAsJson, options_json AS optionsJson,
                   step_count AS stepCount, worker_id AS workerId, lease_version AS leaseVersion,
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

    @Update("""
            UPDATE runtime_turn
               SET status = 'CANCELLED', ended_at = now(), update_at = now()
             WHERE id = #{turnId} AND status = 'QUEUED'
            """)
    int cancelQueued(String turnId);

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
                   ended_at = now(), update_at = now()
             WHERE id = #{turnId} AND status = 'INTERRUPTED'
            """)
    int failInterrupted(@Param("turnId") String turnId,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage);

    @Select("""
            SELECT id, session_id AS sessionId, seq, status, origin, origin_ref AS originRef,
                   client_msg_id AS clientMsgId, run_as_json AS runAsJson, options_json AS optionsJson,
                   step_count AS stepCount, worker_id AS workerId, lease_version AS leaseVersion,
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
                   step_count AS stepCount, worker_id AS workerId, lease_version AS leaseVersion,
                   lease_until AS leaseUntil, error_code AS errorCode, error_message AS errorMessage,
                   prompt_tokens AS promptTokens, completion_tokens AS completionTokens,
                   started_at AS startedAt, ended_at AS endedAt, create_at AS createAt, update_at AS updateAt
            FROM runtime_turn
            WHERE session_id = #{sessionId}
              AND status IN ('RUNNING', 'WAITING_APPROVAL', 'WAITING_CHILD', 'QUEUED')
            ORDER BY seq DESC
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
