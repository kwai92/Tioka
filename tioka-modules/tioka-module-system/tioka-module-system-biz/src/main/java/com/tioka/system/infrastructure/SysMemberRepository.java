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
        mapper.advisoryLockBootstrap();
    }

    public void upsert(String workspaceId, String userId, String roleCode) {
        mapper.upsertMember(workspaceId, userId, roleCode);
    }

    public void upsertWithSource(String workspaceId, String userId, String roleCode, String source) {
        mapper.upsertMemberWithSource(workspaceId, userId, roleCode, source);
    }

    public List<SysMemberDO> findByUserId(String userId) {
        return mapper.listByUserId(userId);
    }

    public List<SysMemberDO> findByUserIdAndSource(String userId, String source) {
        return mapper.listByUserIdAndSource(userId, source);
    }

    public void softDeleteOne(String workspaceId, String userId, String roleCode) {
        mapper.softDeleteOne(workspaceId, userId, roleCode);
    }

    public boolean isMember(String workspaceId, String userId) {
        return mapper.isMember(workspaceId, userId);
    }

    public List<SysMemberDO> listByWorkspace(String workspaceId, int offset, int limit) {
        return mapper.listByWorkspace(workspaceId, offset, limit);
    }

    public long countByWorkspace(String workspaceId) {
        return mapper.countByWorkspace(workspaceId);
    }

    public long countWorkspaceAdmins(String workspaceId) {
        return mapper.countWorkspaceAdmins(workspaceId);
    }

    public void removeUser(String workspaceId, String userId) {
        mapper.softDeleteUser(workspaceId, userId);
    }

    public void replaceWorkspaceRole(String workspaceId, String userId, String roleCode) {
        mapper.softDeleteWorkspaceRoles(workspaceId, userId);
        mapper.upsertMember(workspaceId, userId, roleCode);
    }
}
