package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysGroupMapper;
import com.tioka.system.infrastructure.mapper.SysGroupMemberMapper;
import com.tioka.system.infrastructure.persistence.SysGroupDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysGroupRepository {
    private final SysGroupMapper groups;
    private final SysGroupMemberMapper members;

    public SysGroupRepository(SysGroupMapper groups, SysGroupMemberMapper members) {
        this.groups = groups;
        this.members = members;
    }

    public void insert(SysGroupDO row) {
        groups.insert(row);
    }

    public SysGroupDO findById(String id) {
        return groups.selectById(id);
    }

    public List<SysGroupDO> listAll() {
        return groups.listAll();
    }

    public List<String> groupIdsOfUser(String userId) {
        return members.groupIdsOfUser(userId);
    }

    public List<String> userIdsOfGroup(String groupId) {
        return members.userIdsOfGroup(groupId);
    }

    public SysGroupDO findBySourceAndExternalId(String source, String externalId) {
        return groups.findBySourceAndExternalId(source, externalId);
    }

    /**
     * 全量覆盖用户在指定来源下的组成员关系（FND-05：每次登录同步 OIDC 组）。
     */
    public void replaceUserMembershipsForSource(String userId, String source, List<String> groupIds) {
        members.deleteUserFromSource(userId, source);
        if (groupIds == null) {
            return;
        }
        for (String gid : groupIds) {
            if (gid != null && !gid.isBlank()) {
                members.insert(gid, userId);
            }
        }
    }

    public void replaceMembers(String groupId, List<String> userIds) {
        members.deleteAllOfGroup(groupId);
        if (userIds == null) {
            return;
        }
        for (String uid : userIds) {
            if (uid != null && !uid.isBlank()) {
                members.insert(groupId, uid);
            }
        }
    }
}
