package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysRolePermissionMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysRolePermissionRepository {
    private final SysRolePermissionMapper mapper;

    public SysRolePermissionRepository(SysRolePermissionMapper mapper) {
        this.mapper = mapper;
    }

    public List<String> permissionsOfRole(String roleCode) {
        return mapper.permissionsOfRole(roleCode);
    }
}
