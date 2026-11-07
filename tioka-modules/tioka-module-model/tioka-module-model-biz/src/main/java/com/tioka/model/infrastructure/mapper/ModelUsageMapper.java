package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface ModelUsageMapper extends BaseMapper<ModelUsageDO> {

    @Select("""
            SELECT u.workspace_id AS key,
                   COALESCE(w.name, u.workspace_id, '') AS name,
                   COALESCE(SUM(u.prompt_tokens), 0) AS "promptTokens",
                   COALESCE(SUM(u.completion_tokens), 0) AS "completionTokens",
                   COALESCE(SUM(u.cost_micros), 0) AS "costMicros",
                   COUNT(*) AS calls
            FROM model_usage u
            LEFT JOIN sys_workspace w ON w.id = u.workspace_id
            WHERE (#{workspaceId}::varchar IS NULL OR u.workspace_id = #{workspaceId})
              AND u.create_at >= #{from}
              AND u.create_at <= #{to}
              AND u.workspace_id IS NOT NULL
            GROUP BY u.workspace_id, w.name
            ORDER BY "costMicros" DESC
            """)
    List<Map<String, Object>> summaryByWorkspace(@Param("workspaceId") String workspaceId,
                                                 @Param("from") Instant from,
                                                 @Param("to") Instant to);

    @Select("""
            SELECT u.agent_id AS key,
                   COALESCE(a.name, u.agent_id, '') AS name,
                   COALESCE(SUM(u.prompt_tokens), 0) AS "promptTokens",
                   COALESCE(SUM(u.completion_tokens), 0) AS "completionTokens",
                   COALESCE(SUM(u.cost_micros), 0) AS "costMicros",
                   COUNT(*) AS calls
            FROM model_usage u
            LEFT JOIN agent_agent a ON a.id = u.agent_id
            WHERE (#{workspaceId}::varchar IS NULL OR u.workspace_id = #{workspaceId})
              AND u.create_at >= #{from}
              AND u.create_at <= #{to}
              AND u.agent_id IS NOT NULL
            GROUP BY u.agent_id, a.name
            ORDER BY "costMicros" DESC
            """)
    List<Map<String, Object>> summaryByAgent(@Param("workspaceId") String workspaceId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    @Select("""
            SELECT u.user_id AS key,
                   COALESCE(au.display_name, u.user_id, '') AS name,
                   COALESCE(SUM(u.prompt_tokens), 0) AS "promptTokens",
                   COALESCE(SUM(u.completion_tokens), 0) AS "completionTokens",
                   COALESCE(SUM(u.cost_micros), 0) AS "costMicros",
                   COUNT(*) AS calls
            FROM model_usage u
            LEFT JOIN auth_user au ON au.id::text = u.user_id
            WHERE (#{workspaceId}::varchar IS NULL OR u.workspace_id = #{workspaceId})
              AND u.create_at >= #{from}
              AND u.create_at <= #{to}
              AND u.user_id IS NOT NULL
            GROUP BY u.user_id, au.display_name
            ORDER BY "costMicros" DESC
            """)
    List<Map<String, Object>> summaryByUser(@Param("workspaceId") String workspaceId,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to);

    @Select("""
            SELECT COALESCE(NULLIF(u.binding_id, ''), u.model_ref) AS key,
                   COALESCE(NULLIF(u.model_ref, ''), u.binding_id, '') AS name,
                   COALESCE(SUM(u.prompt_tokens), 0) AS "promptTokens",
                   COALESCE(SUM(u.completion_tokens), 0) AS "completionTokens",
                   COALESCE(SUM(u.cost_micros), 0) AS "costMicros",
                   COUNT(*) AS calls
            FROM model_usage u
            WHERE (#{workspaceId}::varchar IS NULL OR u.workspace_id = #{workspaceId})
              AND u.create_at >= #{from}
              AND u.create_at <= #{to}
              AND COALESCE(NULLIF(u.binding_id, ''), u.model_ref) IS NOT NULL
            GROUP BY COALESCE(NULLIF(u.binding_id, ''), u.model_ref),
                     COALESCE(NULLIF(u.model_ref, ''), u.binding_id, '')
            ORDER BY "costMicros" DESC
            """)
    List<Map<String, Object>> summaryByModel(@Param("workspaceId") String workspaceId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    @Select("""
            SELECT to_char(date_trunc('day', u.create_at), 'YYYY-MM-DD') AS day,
                   COALESCE(SUM(u.prompt_tokens), 0) AS "promptTokens",
                   COALESCE(SUM(u.completion_tokens), 0) AS "completionTokens",
                   COALESCE(SUM(u.cost_micros), 0) AS "costMicros",
                   COUNT(*) AS calls
            FROM model_usage u
            WHERE (#{workspaceId}::varchar IS NULL OR u.workspace_id = #{workspaceId})
              AND u.create_at >= #{from}
              AND u.create_at <= #{to}
              AND (
                    (#{scopeType} = 'workspace' AND u.workspace_id = #{scopeId})
                 OR (#{scopeType} = 'agent' AND u.agent_id = #{scopeId})
                 OR (#{scopeType} = 'user' AND u.user_id = #{scopeId})
                 OR (#{scopeType} = 'model' AND COALESCE(NULLIF(u.binding_id, ''), u.model_ref) = #{scopeId})
              )
            GROUP BY 1
            ORDER BY 1
            """)
    List<Map<String, Object>> trendByDay(@Param("scopeType") String scopeType,
                                         @Param("scopeId") String scopeId,
                                         @Param("workspaceId") String workspaceId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    @Select("""
            SELECT COALESCE(SUM(prompt_tokens), 0) AS "promptTokens",
                   COALESCE(SUM(completion_tokens), 0) AS "completionTokens",
                   COALESCE(SUM(cost_micros), 0) AS "costMicros",
                   COUNT(*) AS calls
            FROM model_usage
            WHERE session_id = #{sessionId}
            """)
    Map<String, Object> sessionAggregate(@Param("sessionId") String sessionId);

    /** GOV-03 对账：workspace / user / agent 本窗口 token 合计。 */
    @Select("""
            SELECT COALESCE(SUM(prompt_tokens), 0) + COALESCE(SUM(completion_tokens), 0)
            FROM model_usage
            WHERE create_at >= #{from} AND create_at < #{to}
              AND (
                   (#{scopeType} = 'workspace' AND workspace_id = #{scopeId})
                OR (#{scopeType} = 'user' AND user_id = #{scopeId})
                OR (#{scopeType} = 'agent' AND agent_id = #{scopeId})
              )
            """)
    Long sumTokensByScope(@Param("scopeType") String scopeType,
                          @Param("scopeId") String scopeId,
                          @Param("from") Instant from,
                          @Param("to") Instant to);
}
