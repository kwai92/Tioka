package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysWorkspaceDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysWorkspaceMapper extends BaseMapper<SysWorkspaceDO> {
}
