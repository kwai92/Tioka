package com.tioka.mcp.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.mcp.infrastructure.persistence.McpServerDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface McpServerMapper extends BaseMapper<McpServerDO> {}
