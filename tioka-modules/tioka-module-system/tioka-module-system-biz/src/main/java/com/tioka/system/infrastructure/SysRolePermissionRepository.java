package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysRolePermissionMapper;
import com.tioka.system.infrastructure.mapper.SysRolePermissionWriteMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysRolePermissionRepository {
    private final SysRolePermissionMapper mapper;
    private final SysRolePermissionWriteMapper writer;

    public SysRolePermissionRepository(SysRolePermissionMapper mapper,
                                       SysRolePermissionWriteMapper writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    public List<String> permissionsOfRole(String roleCode) {
        return mapper.permissionsOfRole(roleCode);
    }

    public int deletePermissions(String roleCode) {
        return writer.deletePermissions(roleCode);
    }

    public int insertPermission(String roleCode, String permission) {
        return writer.insertPermission(roleCode, permission);
    }
}
