package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthUserMapper extends BaseMapper<AuthUserDO> {
    @Select("SELECT COALESCE(MAX(id), 0) FROM auth_user")
    Long selectMaxId();
}
