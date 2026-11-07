package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 按资源类型查 workspace / visibility / owner（FND-04 ResourceGuard）。
 * 表名白名单，禁止外部拼接。
 */
@Mapper
public interface ResourceLookupMapper {

    @Select("""
            SELECT id, workspace_id AS workspaceId, owner_id AS ownerId, visibility
            FROM agent_agent
            WHERE id = #{id} AND deleted = FALSE
            """)
    Map<String, Object> findAgent(@Param("id") String id);

    @Select("""
            SELECT id, workspace_id AS workspaceId, owner_id AS ownerId, visibility
            FROM kb_base
            WHERE id = #{id} AND deleted = FALSE
            """)
    Map<String, Object> findKb(@Param("id") String id);

    @Select("""
            SELECT id, workspace_id AS workspaceId, owner_id AS ownerId, visibility
            FROM flow_draft
            WHERE id = #{id} AND deleted = FALSE
            """)
    Map<String, Object> findFlow(@Param("id") String id);

    @Select("""
            SELECT id, workspace_id AS workspaceId, owner_id AS ownerId, visibility
            FROM mcp_server
            WHERE id = #{id} AND deleted = FALSE
            """)
    Map<String, Object> findMcp(@Param("id") String id);

    @Select("""
            SELECT id, workspace_id AS workspaceId, owner_id AS ownerId, visibility
            FROM skill_package
            WHERE id = #{id} AND deleted = FALSE
            """)
    Map<String, Object> findSkill(@Param("id") String id);

    @Select("""
            SELECT id FROM agent_agent
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
            """)
    List<String> listAgentIds(@Param("workspaceId") String workspaceId);

    @Select("""
            SELECT id FROM kb_base
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
            """)
    List<String> listKbIds(@Param("workspaceId") String workspaceId);

    @Select("""
            SELECT id FROM flow_draft
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
            """)
    List<String> listFlowIds(@Param("workspaceId") String workspaceId);

    @Select("""
            SELECT id FROM mcp_server
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
            """)
    List<String> listMcpIds(@Param("workspaceId") String workspaceId);

    @Select("""
            SELECT id FROM skill_package
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
            """)
    List<String> listSkillIds(@Param("workspaceId") String workspaceId);

    @Select("""
            SELECT id FROM agent_agent
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
              AND (${filterSql})
            """)
    List<String> filterAgentIds(@Param("workspaceId") String workspaceId,
                                @Param("filterSql") String filterSql,
                                @Param("uid") String uid,
                                @Param("gids") List<String> gids,
                                @Param("levels") List<String> levels);

    @Select("""
            SELECT id FROM kb_base
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
              AND (${filterSql})
            """)
    List<String> filterKbIds(@Param("workspaceId") String workspaceId,
                             @Param("filterSql") String filterSql,
                             @Param("uid") String uid,
                             @Param("gids") List<String> gids,
                             @Param("levels") List<String> levels);
}
