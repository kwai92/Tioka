package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface SysRolePermissionWriteMapper {

        int insertRole(@Param("code") String code,
                   @Param("name") String name,
                   @Param("scope") String scope,
                   @Param("builtin") boolean builtin);

        int updateRoleName(@Param("code") String code, @Param("name") String name);

        int softDeleteRole(@Param("code") String code);

        int deletePermissions(@Param("roleCode") String roleCode);

        int insertPermission(@Param("roleCode") String roleCode, @Param("permission") String permission);

        long countMembersWithRole(@Param("roleCode") String roleCode);
}
