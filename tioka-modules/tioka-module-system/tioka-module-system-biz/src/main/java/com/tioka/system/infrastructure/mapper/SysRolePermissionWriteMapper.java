package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysRolePermissionWriteMapper {

    @Insert("""
            INSERT INTO sys_role (code, name, scope, builtin, create_at, update_at, create_by, update_by, deleted)
            VALUES (#{code}, #{name}, #{scope}, #{builtin}, now(), now(), 'system', 'system', FALSE)
            """)
    int insertRole(@Param("code") String code,
                   @Param("name") String name,
                   @Param("scope") String scope,
                   @Param("builtin") boolean builtin);

    @Update("""
            UPDATE sys_role SET name = #{name}, update_at = now()
            WHERE code = #{code} AND deleted = FALSE
            """)
    int updateRoleName(@Param("code") String code, @Param("name") String name);

    @Update("""
            UPDATE sys_role SET deleted = TRUE, update_at = now()
            WHERE code = #{code}
            """)
    int softDeleteRole(@Param("code") String code);

    @Delete("DELETE FROM sys_role_permission WHERE role_code = #{roleCode}")
    int deletePermissions(@Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO sys_role_permission (role_code, permission, create_at, create_by)
            VALUES (#{roleCode}, #{permission}, now(), 'system')
            ON CONFLICT DO NOTHING
            """)
    int insertPermission(@Param("roleCode") String roleCode, @Param("permission") String permission);

    @Select("""
            SELECT COUNT(*) FROM sys_member
            WHERE role_code = #{roleCode} AND deleted = FALSE
            """)
    long countMembersWithRole(@Param("roleCode") String roleCode);
}
