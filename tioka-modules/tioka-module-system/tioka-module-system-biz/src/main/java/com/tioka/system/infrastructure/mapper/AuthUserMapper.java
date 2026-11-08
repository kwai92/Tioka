package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface AuthUserMapper extends BaseMapper<AuthUserDO> {
        long nextId();
}
