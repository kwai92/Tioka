package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysUserRoleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRoleDO> {

        List<String> rolesOfUser(@Param("userId") String userId);

        int upsert(@Param("userId") String userId, @Param("roleCode") String roleCode);

        int delete(@Param("userId") String userId, @Param("roleCode") String roleCode);

        boolean existsEnabledSystemAdmin();

        long countByRole(@Param("roleCode") String roleCode);
}
