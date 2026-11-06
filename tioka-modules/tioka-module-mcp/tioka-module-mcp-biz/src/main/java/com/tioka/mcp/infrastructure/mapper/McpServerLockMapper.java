package com.tioka.mcp.infrastructure.mapper;

import com.tioka.mcp.infrastructure.persistence.McpServerDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface McpServerLockMapper {
    @Select("""
            SELECT id, name, base_url AS baseUrl, header_secret_ref AS headerSecretRef, status,
                   workspace_id AS workspaceId, owner_id AS ownerId,
                   create_at AS createAt, update_at AS updateAt, create_by AS createBy,
                   update_by AS updateBy, deleted
            FROM mcp_server
            WHERE id = #{id} AND deleted = FALSE
            FOR SHARE
            """)
    McpServerDO selectForShare(@Param("id") String id);
}
