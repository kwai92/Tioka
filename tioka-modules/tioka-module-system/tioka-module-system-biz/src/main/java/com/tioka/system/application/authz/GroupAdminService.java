package com.tioka.system.application.authz;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.infrastructure.SysGroupRepository;
import com.tioka.system.infrastructure.persistence.SysGroupDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class GroupAdminService {
    private final SysGroupRepository groups;
    private final PrincipalLoader principalLoader;

    public GroupAdminService(SysGroupRepository groups, PrincipalLoader principalLoader) {
        this.groups = groups;
        this.principalLoader = principalLoader;
    }

    public List<GroupView> list() {
        List<GroupView> out = new ArrayList<>();
        for (SysGroupDO g : groups.listAll()) {
            out.add(toView(g));
        }
        return out;
    }

    @Transactional
    public GroupView create(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 必填");
        }
        SysGroupDO row = new SysGroupDO();
        row.setId(Ids.newId("grp"));
        row.setName(name.trim());
        row.setSource("local");
        groups.insert(row);
        return toView(row);
    }

    @Transactional
    public GroupView replaceMembers(String groupId, List<String> userIds) {
        SysGroupDO g = groups.findById(groupId);
        if (g == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "组不存在");
        }
        if (g.getSource() != null && g.getSource().startsWith("oidc:")) {
            throw new BizException(ErrorCode.FORBIDDEN, "OIDC 来源的组只读");
        }
        List<String> previous = groups.userIdsOfGroup(groupId);
        groups.replaceMembers(groupId, userIds);
        for (String uid : previous) {
            principalLoader.evictGroups(uid);
        }
        if (userIds != null) {
            for (String uid : userIds) {
                principalLoader.evictGroups(uid);
            }
        }
        return toView(groups.findById(groupId));
    }

    private GroupView toView(SysGroupDO g) {
        return new GroupView(g.getId(), g.getName(), g.getSource(),
                groups.userIdsOfGroup(g.getId()));
    }

    public record GroupView(String id, String name, String source, List<String> memberIds) {}
}
