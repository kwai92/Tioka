package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeApprovalDO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuntimeApprovalMapper {

    @Select("""
            SELECT id, workspace_id AS workspaceId, subject_type AS subjectType, subject_id AS subjectId,
                   item_id AS itemId, kind, tool_name AS toolName, args_json AS argsJson, card_json AS cardJson,
                   policy, assignee_json AS assigneeJson, status, decided_by AS decidedBy,
                   decided_at AS decidedAt, answer_json AS answerJson, expires_at AS expiresAt,
                   create_at AS createAt, update_at AS updateAt, create_by AS createBy, update_by AS updateBy
              FROM runtime_approval WHERE id = #{id}
            """)
    RuntimeApprovalDO findById(String id);

    @Select("""
            SELECT id, workspace_id AS workspaceId, subject_type AS subjectType, subject_id AS subjectId,
                   item_id AS itemId, kind, tool_name AS toolName, args_json AS argsJson, card_json AS cardJson,
                   policy, assignee_json AS assigneeJson, status, decided_by AS decidedBy,
                   decided_at AS decidedAt, answer_json AS answerJson, expires_at AS expiresAt,
                   create_at AS createAt, update_at AS updateAt, create_by AS createBy, update_by AS updateBy
              FROM runtime_approval WHERE id = #{id} FOR UPDATE
            """)
    RuntimeApprovalDO findForUpdate(String id);

    @Insert("""
            INSERT INTO runtime_approval (
              id, workspace_id, subject_type, subject_id, item_id, kind, tool_name, args_json, card_json,
              policy, assignee_json, status, expires_at, create_at, update_at, create_by, update_by
            ) VALUES (
              #{id}, #{workspaceId}, #{subjectType}, #{subjectId}, #{itemId}, #{kind}, #{toolName},
              #{argsJson}, #{cardJson}, #{policy}, #{assigneeJson}, #{status}, #{expiresAt},
              now(), now(), COALESCE(#{createBy}, 'system'), COALESCE(#{updateBy}, 'system')
            )
            """)
    void insert(RuntimeApprovalDO row);

    @Update("""
            UPDATE runtime_approval
               SET status = #{status},
                   decided_by = #{decidedBy},
                   decided_at = now(),
                   answer_json = #{answerJson},
                   update_at = now(),
                   update_by = COALESCE(#{updateBy}, 'system')
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int decide(@Param("id") String id,
               @Param("status") String status,
               @Param("decidedBy") String decidedBy,
               @Param("answerJson") String answerJson,
               @Param("updateBy") String updateBy);

    @Select("""
            SELECT id, workspace_id AS workspaceId, subject_type AS subjectType, subject_id AS subjectId,
                   item_id AS itemId, kind, tool_name AS toolName, args_json AS argsJson, card_json AS cardJson,
                   policy, assignee_json AS assigneeJson, status, decided_by AS decidedBy,
                   decided_at AS decidedAt, answer_json AS answerJson, expires_at AS expiresAt,
                   create_at AS createAt, update_at AS updateAt, create_by AS createBy, update_by AS updateBy
              FROM runtime_approval
             WHERE subject_type = #{subjectType} AND subject_id = #{subjectId}
            """)
    List<RuntimeApprovalDO> listBySubject(@Param("subjectType") String subjectType,
                                          @Param("subjectId") String subjectId);

    @Select("""
            SELECT COUNT(*) FROM runtime_approval
             WHERE subject_type = #{subjectType} AND subject_id = #{subjectId} AND status = 'PENDING'
            """)
    int countPending(@Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @Select("""
            SELECT id, workspace_id AS workspaceId, subject_type AS subjectType, subject_id AS subjectId,
                   item_id AS itemId, kind, tool_name AS toolName, args_json AS argsJson, card_json AS cardJson,
                   policy, assignee_json AS assigneeJson, status, decided_by AS decidedBy,
                   decided_at AS decidedAt, answer_json AS answerJson, expires_at AS expiresAt,
                   create_at AS createAt, update_at AS updateAt, create_by AS createBy, update_by AS updateBy
              FROM runtime_approval
             WHERE item_id = #{itemId}
             ORDER BY create_at DESC
             LIMIT 1
            """)
    RuntimeApprovalDO findByItemId(String itemId);

    @Select("""
            UPDATE runtime_approval
               SET status = 'EXPIRED',
                   decided_by = COALESCE(#{decidedBy}, 'system'),
                   decided_at = now(),
                   update_at = now(),
                   update_by = 'system'
             WHERE status = 'PENDING' AND expires_at < #{now}
         RETURNING id
            """)
    List<String> expireDue(@Param("now") LocalDateTime now, @Param("decidedBy") String decidedBy);

    @Update("""
            UPDATE runtime_approval
               SET status = 'EXPIRED',
                   decided_by = #{decidedBy},
                   decided_at = now(),
                   update_at = now(),
                   update_by = 'system'
             WHERE subject_type = #{subjectType}
               AND subject_id = #{subjectId}
               AND status = 'PENDING'
            """)
    int expirePendingForSubject(@Param("subjectType") String subjectType,
                                @Param("subjectId") String subjectId,
                                @Param("decidedBy") String decidedBy);

    @Select("""
            SELECT t.id, t.session_id AS sessionId
              FROM runtime_turn t
             WHERE t.status = 'WAITING_APPROVAL'
               AND NOT EXISTS (
                     SELECT 1 FROM runtime_approval a
                      WHERE a.subject_type = 'turn' AND a.subject_id = t.id AND a.status = 'PENDING')
             LIMIT 50
            """)
    List<WaitingTurnRow> findWaitingWithoutPending();

    record WaitingTurnRow(String id, String sessionId) {}
}
