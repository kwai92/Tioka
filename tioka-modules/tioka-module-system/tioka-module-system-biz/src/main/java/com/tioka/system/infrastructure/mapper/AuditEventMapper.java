package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditEventMapper {

    @Insert("""
            INSERT INTO audit_event
              (id, ts, workspace_id, actor_type, actor_id, action,
               resource_type, resource_id, outcome, reason, trace_id, client_ip, details_json)
            VALUES
              (#{id}, #{ts}, #{workspaceId}, #{actorType}, #{actorId}, #{action},
               #{resourceType}, #{resourceId}, #{outcome}, #{reason}, #{traceId}, #{clientIp}, #{detailsJson})
            """)
    int insert(@Param("id") String id,
               @Param("ts") Instant ts,
               @Param("workspaceId") String workspaceId,
               @Param("actorType") String actorType,
               @Param("actorId") String actorId,
               @Param("action") String action,
               @Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId,
               @Param("outcome") String outcome,
               @Param("reason") String reason,
               @Param("traceId") String traceId,
               @Param("clientIp") String clientIp,
               @Param("detailsJson") String detailsJson);

    @Select("""
            SELECT id, ts, workspace_id AS workspaceId, actor_type AS actorType, actor_id AS actorId,
                   action, resource_type AS resourceType, resource_id AS resourceId,
                   outcome, reason, trace_id AS traceId, client_ip AS clientIp, details_json AS detailsJson
            FROM audit_event
            WHERE (#{workspaceId}::varchar IS NULL OR workspace_id = #{workspaceId})
              AND (#{actorId}::varchar IS NULL OR actor_id = #{actorId})
              AND (#{action}::varchar IS NULL OR action = #{action})
              AND (#{outcome}::varchar IS NULL OR outcome = #{outcome})
            ORDER BY ts DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> query(@Param("workspaceId") String workspaceId,
                                    @Param("actorId") String actorId,
                                    @Param("action") String action,
                                    @Param("outcome") String outcome,
                                    @Param("limit") int limit);
}
