package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.dataobject.SysMemberDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysMemberMapper extends BaseMapper<SysMemberDO> {
    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM sys_member m
              JOIN auth_user u ON u.id::text = m.user_id
              WHERE m.role_code = 'tenant_admin'
                AND m.deleted = FALSE
                AND u.enabled = TRUE
                AND u.deleted = FALSE
            )
            """)
    boolean existsEnabledTenantAdmin();

    /** PostgreSQL void 函数经 JDBC 为 null；返回 Object 避免 MyBatis 映射 void */
    @Select("SELECT pg_advisory_xact_lock(hashtext('tioka.bootstrap'))")
    Object advisoryLockBootstrap();

    @Insert("""
            INSERT INTO sys_member (workspace_id, user_id, role_code, create_at, update_at, create_by, update_by, deleted)
            VALUES (#{workspaceId}, #{userId}, #{roleCode}, now(), now(), 'system', 'system', FALSE)
            ON CONFLICT (workspace_id, user_id, role_code) DO NOTHING
            """)
    void upsertMember(@Param("workspaceId") String workspaceId,
                      @Param("userId") String userId,
                      @Param("roleCode") String roleCode);

    @Select("""
            SELECT workspace_id AS workspaceId, user_id AS userId, role_code AS roleCode, deleted AS deleted
            FROM sys_member
            WHERE user_id = #{userId} AND deleted = FALSE
            """)
    java.util.List<SysMemberDO> listByUserId(@Param("userId") String userId);
}
