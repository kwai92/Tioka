package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.dataobject.SysMemberDO;
import com.tioka.system.infrastructure.mapper.SysMemberMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysMemberRepository {
    private final SysMemberMapper mapper;

    public SysMemberRepository(SysMemberMapper mapper) {
        this.mapper = mapper;
    }

    public boolean existsEnabledTenantAdmin() {
        return mapper.existsEnabledTenantAdmin();
    }

    public void advisoryLockBootstrap() {
        mapper.advisoryLockBootstrap(); // 阻塞直至拿到事务级咨询锁
    }

    public void upsert(String workspaceId, String userId, String roleCode) {
        mapper.upsertMember(workspaceId, userId, roleCode);
    }

    public List<SysMemberDO> findByUserId(String userId) {
        return mapper.listByUserId(userId);
    }
}
