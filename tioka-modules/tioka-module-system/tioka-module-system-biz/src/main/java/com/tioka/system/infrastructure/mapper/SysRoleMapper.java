package com.tioka.system.infrastructure.mapper;

import com.tioka.system.infrastructure.persistence.SysRoleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface SysRoleMapper {
        SysRoleDO findByCode(@Param("code") String code);

        java.util.List<SysRoleDO> listAll();
}
