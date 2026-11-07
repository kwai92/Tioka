package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysApiKeyDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysApiKeyMapper extends BaseMapper<SysApiKeyDO> {
}
