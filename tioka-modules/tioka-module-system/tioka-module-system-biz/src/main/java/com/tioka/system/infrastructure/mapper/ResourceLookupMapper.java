package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/**
 * 按资源类型查 workspace / visibility / owner（FND-04 ResourceGuard）。
 * 表名白名单，禁止外部拼接。
 */
@Mapper
public interface ResourceLookupMapper {

        Map<String, Object> findAgent(@Param("id") String id);

        Map<String, Object> findKb(@Param("id") String id);

        Map<String, Object> findFlow(@Param("id") String id);

        Map<String, Object> findMcp(@Param("id") String id);

        Map<String, Object> findSkill(@Param("id") String id);

        List<String> listAgentIds(@Param("workspaceId") String workspaceId);

        List<String> listKbIds(@Param("workspaceId") String workspaceId);

        List<String> listFlowIds(@Param("workspaceId") String workspaceId);

        List<String> listMcpIds(@Param("workspaceId") String workspaceId);

        List<String> listSkillIds(@Param("workspaceId") String workspaceId);

        List<String> filterAgentIds(@Param("workspaceId") String workspaceId,
                                @Param("filterSql") String filterSql,
                                @Param("uid") String uid,
                                @Param("gids") List<String> gids,
                                @Param("levels") List<String> levels);

        List<String> filterKbIds(@Param("workspaceId") String workspaceId,
                             @Param("filterSql") String filterSql,
                             @Param("uid") String uid,
                             @Param("gids") List<String> gids,
                             @Param("levels") List<String> levels);
}
