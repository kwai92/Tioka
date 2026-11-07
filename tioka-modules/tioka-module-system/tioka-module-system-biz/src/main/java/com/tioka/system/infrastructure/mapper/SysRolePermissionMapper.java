package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface SysRolePermissionMapper {
        List<String> permissionsOfRole(String roleCode);
}
