package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysMemberDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface SysMemberMapper extends BaseMapper<SysMemberDO> {
        boolean existsEnabledSystemAdmin();

    /** PostgreSQL void 函数经 JDBC 为 null；返回 Object 避免 MyBatis 映射 void */
        Object advisoryLockBootstrap();

        void upsertMember(@Param("workspaceId") String workspaceId,
                      @Param("userId") String userId,
                      @Param("roleCode") String roleCode);

        void upsertMemberWithSource(@Param("workspaceId") String workspaceId,
                                @Param("userId") String userId,
                                @Param("roleCode") String roleCode,
                                @Param("source") String source);

        java.util.List<SysMemberDO> listByUserId(@Param("userId") String userId);

        java.util.List<SysMemberDO> listByUserIdAndSource(@Param("userId") String userId,
                                                      @Param("source") String source);

        boolean isMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

        java.util.List<SysMemberDO> listByWorkspace(@Param("workspaceId") String workspaceId,
                                                @Param("offset") int offset,
                                                @Param("limit") int limit);

        long countByWorkspace(@Param("workspaceId") String workspaceId);

        long countWorkspaceAdmins(@Param("workspaceId") String workspaceId);

        int softDeleteUser(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

        int softDeleteWorkspaceRoles(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

        int softDeleteOne(@Param("workspaceId") String workspaceId,
                      @Param("userId") String userId,
                      @Param("roleCode") String roleCode);
}
