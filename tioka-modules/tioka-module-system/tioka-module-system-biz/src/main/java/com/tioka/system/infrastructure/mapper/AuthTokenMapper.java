package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.AuthTokenDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthTokenMapper extends BaseMapper<AuthTokenDO> {
}
