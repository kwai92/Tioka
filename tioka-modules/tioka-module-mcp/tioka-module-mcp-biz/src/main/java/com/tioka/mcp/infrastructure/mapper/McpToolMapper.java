package com.tioka.mcp.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.mcp.infrastructure.persistence.McpToolDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface McpToolMapper extends BaseMapper<McpToolDO> {
    /** 全量替换子表：物理清除，避免软删与复合主键冲突 */
    @Delete("DELETE FROM mcp_tool WHERE server_id = #{serverId}")
    int physicalDeleteByServerId(@Param("serverId") String serverId);
}
