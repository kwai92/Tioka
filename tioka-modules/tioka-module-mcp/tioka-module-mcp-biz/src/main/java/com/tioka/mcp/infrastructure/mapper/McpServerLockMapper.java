package com.tioka.mcp.infrastructure.mapper;

import com.tioka.mcp.infrastructure.persistence.McpServerDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface McpServerLockMapper {
        McpServerDO selectForShare(@Param("id") String id);
}
