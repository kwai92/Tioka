package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.dataobject.SysMemberDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
            INSERT INTO sys_member (workspace_id, user_id, role_code, source, create_at, update_at, create_by, update_by, deleted)
            VALUES (#{workspaceId}, #{userId}, #{roleCode}, 'manual', now(), now(), 'system', 'system', FALSE)
            ON CONFLICT (workspace_id, user_id, role_code) DO UPDATE
              SET deleted = FALSE, update_at = now()
            """)
    void upsertMember(@Param("workspaceId") String workspaceId,
                      @Param("userId") String userId,
                      @Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO sys_member (workspace_id, user_id, role_code, source, create_at, update_at, create_by, update_by, deleted)
            VALUES (#{workspaceId}, #{userId}, #{roleCode}, #{source}, now(), now(), 'system', 'system', FALSE)
            ON CONFLICT (workspace_id, user_id, role_code) DO UPDATE
              SET deleted = FALSE, source = EXCLUDED.source, update_at = now(), update_by = 'system'
            """)
    void upsertMemberWithSource(@Param("workspaceId") String workspaceId,
                                @Param("userId") String userId,
                                @Param("roleCode") String roleCode,
                                @Param("source") String source);

    @Select("""
            SELECT workspace_id AS workspaceId, user_id AS userId, role_code AS roleCode,
                   source AS source, deleted AS deleted
            FROM sys_member
            WHERE user_id = #{userId} AND deleted = FALSE
            """)
    java.util.List<SysMemberDO> listByUserId(@Param("userId") String userId);

    @Select("""
            SELECT workspace_id AS workspaceId, user_id AS userId, role_code AS roleCode,
                   source AS source, deleted AS deleted
            FROM sys_member
            WHERE user_id = #{userId} AND source = #{source} AND deleted = FALSE
            """)
    java.util.List<SysMemberDO> listByUserIdAndSource(@Param("userId") String userId,
                                                      @Param("source") String source);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM sys_member
              WHERE workspace_id = #{workspaceId} AND user_id = #{userId} AND deleted = FALSE
            )
            """)
    boolean isMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    @Select("""
            SELECT workspace_id AS workspaceId, user_id AS userId, role_code AS roleCode,
                   source AS source, deleted AS deleted
            FROM sys_member
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
              AND role_code <> 'tenant_admin'
            ORDER BY user_id, role_code
            LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<SysMemberDO> listByWorkspace(@Param("workspaceId") String workspaceId,
                                                @Param("offset") int offset,
                                                @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM sys_member
            WHERE workspace_id = #{workspaceId} AND deleted = FALSE
              AND role_code <> 'tenant_admin'
            """)
    long countByWorkspace(@Param("workspaceId") String workspaceId);

    @Select("""
            SELECT COUNT(*) FROM sys_member
            WHERE workspace_id = #{workspaceId} AND role_code = 'workspace_admin' AND deleted = FALSE
            """)
    long countWorkspaceAdmins(@Param("workspaceId") String workspaceId);

    @Update("""
            UPDATE sys_member SET deleted = TRUE, update_at = now()
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId} AND deleted = FALSE
            """)
    int softDeleteUser(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    @Update("""
            UPDATE sys_member SET deleted = TRUE, update_at = now()
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId}
              AND role_code <> 'tenant_admin' AND deleted = FALSE
            """)
    int softDeleteWorkspaceRoles(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    @Update("""
            UPDATE sys_member SET deleted = TRUE, update_at = now(), update_by = 'system'
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId}
              AND role_code = #{roleCode} AND deleted = FALSE
            """)
    int softDeleteOne(@Param("workspaceId") String workspaceId,
                      @Param("userId") String userId,
                      @Param("roleCode") String roleCode);
}
