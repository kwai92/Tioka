package com.tioka.agent.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.agent.infrastructure.persistence.AgentSandboxDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface AgentSandboxMapper extends BaseMapper<AgentSandboxDO> {}
