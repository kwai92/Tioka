package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysUserRoleDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRoleDO> {

    @Select("SELECT role_code FROM sys_user_role WHERE user_id = #{userId}")
    List<String> rolesOfUser(@Param("userId") String userId);

    @Insert("""
            INSERT INTO sys_user_role (user_id, role_code, create_at, create_by)
            VALUES (#{userId}, #{roleCode}, now(), 'system')
            ON CONFLICT DO NOTHING
            """)
    int upsert(@Param("userId") String userId, @Param("roleCode") String roleCode);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId} AND role_code = #{roleCode}")
    int delete(@Param("userId") String userId, @Param("roleCode") String roleCode);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM sys_user_role ur
              JOIN auth_user u ON u.id::text = ur.user_id
              WHERE ur.role_code = 'tenant_admin'
                AND u.enabled = TRUE
                AND u.deleted = FALSE
            )
            """)
    boolean existsEnabledTenantAdmin();

    @Select("SELECT COUNT(*) FROM sys_user_role WHERE role_code = #{roleCode}")
    long countByRole(@Param("roleCode") String roleCode);
}
