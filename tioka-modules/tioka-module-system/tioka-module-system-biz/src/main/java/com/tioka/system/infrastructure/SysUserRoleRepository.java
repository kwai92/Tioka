package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysUserRoleRepository {
    private final SysUserRoleMapper mapper;

    public SysUserRoleRepository(SysUserRoleMapper mapper) {
        this.mapper = mapper;
    }

    public List<String> rolesOfUser(String userId) {
        return mapper.rolesOfUser(userId);
    }

    public void upsert(String userId, String roleCode) {
        mapper.upsert(userId, roleCode);
    }

    public void delete(String userId, String roleCode) {
        mapper.delete(userId, roleCode);
    }

    public boolean existsEnabledSystemAdmin() {
        return mapper.existsEnabledSystemAdmin();
    }

    public long countByRole(String roleCode) {
        return mapper.countByRole(roleCode);
    }
}
