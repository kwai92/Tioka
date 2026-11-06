package com.tioka.system.infrastructure.mapper;

import com.tioka.system.infrastructure.persistence.SysRoleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper {
    @Select("""
            SELECT code, name, scope, builtin
            FROM sys_role
            WHERE code = #{code} AND deleted = FALSE
            """)
    SysRoleDO findByCode(@Param("code") String code);
}
