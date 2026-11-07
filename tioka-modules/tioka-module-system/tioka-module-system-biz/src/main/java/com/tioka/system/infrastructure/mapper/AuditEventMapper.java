package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditEventMapper {

    @Insert("""
            INSERT INTO audit_event
              (id, ts, workspace_id, actor_type, actor_id, action,
               resource_type, resource_id, outcome, reason, trace_id, request_id, client_ip, details_json, forwarded)
            VALUES
              (#{id}, #{ts}, #{workspaceId}, #{actorType}, #{actorId}, #{action},
               #{resourceType}, #{resourceId}, #{outcome}, #{reason}, #{traceId}, #{requestId}, #{clientIp}, #{detailsJson}, FALSE)
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
               @Param("requestId") String requestId,
               @Param("clientIp") String clientIp,
               @Param("detailsJson") String detailsJson);

    @Select("""
            SELECT id, ts, workspace_id AS workspaceId, actor_type AS actorType, actor_id AS actorId,
                   action, resource_type AS resourceType, resource_id AS resourceId,
                   outcome, reason, trace_id AS traceId, request_id AS requestId, client_ip AS clientIp,
                   details_json AS detailsJson, forwarded
            FROM audit_event
            WHERE (#{workspaceId}::varchar IS NULL OR workspace_id = #{workspaceId})
              AND (#{actorId}::varchar IS NULL OR actor_id = #{actorId})
              AND (#{action}::varchar IS NULL OR action = #{action})
              AND (#{resourceType}::varchar IS NULL OR resource_type = #{resourceType})
              AND (#{resourceId}::varchar IS NULL OR resource_id = #{resourceId})
              AND (#{outcome}::varchar IS NULL OR outcome = #{outcome})
              AND (#{from}::timestamptz IS NULL OR ts >= #{from})
              AND (#{to}::timestamptz IS NULL OR ts <= #{to})
            ORDER BY ts DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> query(@Param("workspaceId") String workspaceId,
                                    @Param("actorId") String actorId,
                                    @Param("action") String action,
                                    @Param("resourceType") String resourceType,
                                    @Param("resourceId") String resourceId,
                                    @Param("outcome") String outcome,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("""
            SELECT id, ts, workspace_id AS workspaceId, actor_type AS actorType, actor_id AS actorId,
                   action, resource_type AS resourceType, resource_id AS resourceId,
                   outcome, reason, trace_id AS traceId, request_id AS requestId, client_ip AS clientIp,
                   details_json AS detailsJson, forwarded
            FROM audit_event
            WHERE forwarded = FALSE
            ORDER BY ts ASC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> listUnforwarded(@Param("limit") int limit);

    @Update("UPDATE audit_event SET forwarded = TRUE WHERE id = #{id}")
    int markForwardedOne(@Param("id") String id);

    @Delete("""
            DELETE FROM audit_event
            WHERE forwarded = TRUE AND ts < #{before}
            """)
    int deleteExpiredForwarded(@Param("before") Instant before);
}
