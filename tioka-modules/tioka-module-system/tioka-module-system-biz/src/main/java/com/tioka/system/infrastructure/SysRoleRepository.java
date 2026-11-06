package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysRoleMapper;
import com.tioka.system.infrastructure.persistence.SysRoleDO;
import org.springframework.stereotype.Repository;

@Repository
public class SysRoleRepository {
    private final SysRoleMapper mapper;

    public SysRoleRepository(SysRoleMapper mapper) {
        this.mapper = mapper;
    }

    public SysRoleDO findByCode(String code) {
        return mapper.findByCode(code);
    }
}
