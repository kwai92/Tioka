package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRolePermissionMapper {
    @Select("SELECT permission FROM sys_role_permission WHERE role_code = #{roleCode}")
    List<String> permissionsOfRole(String roleCode);
}
