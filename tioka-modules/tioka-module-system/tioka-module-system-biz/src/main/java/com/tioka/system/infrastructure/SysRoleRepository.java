package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysRoleMapper;
import com.tioka.system.infrastructure.mapper.SysRolePermissionWriteMapper;
import com.tioka.system.infrastructure.persistence.SysRoleDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysRoleRepository {
    private final SysRoleMapper mapper;
    private final SysRolePermissionWriteMapper writer;

    public SysRoleRepository(SysRoleMapper mapper, SysRolePermissionWriteMapper writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    public SysRoleDO findByCode(String code) {
        return mapper.findByCode(code);
    }

    public List<SysRoleDO> listAll() {
        return mapper.listAll();
    }

    public int insertRole(String code, String name, String scope, boolean builtin) {
        return writer.insertRole(code, name, scope, builtin);
    }

    public int updateRoleName(String code, String name) {
        return writer.updateRoleName(code, name);
    }

    public int softDeleteRole(String code) {
        return writer.softDeleteRole(code);
    }

    public long countMembersWithRole(String roleCode) {
        return writer.countMembersWithRole(roleCode);
    }
}
