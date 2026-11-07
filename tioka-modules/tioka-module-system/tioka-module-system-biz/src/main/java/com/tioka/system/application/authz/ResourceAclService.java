package com.tioka.system.application.authz;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.system.api.authz.ResourceAclApi;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysGroupRepository;
import com.tioka.system.infrastructure.SysResourceAclRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.persistence.SysGroupDO;
import com.tioka.system.infrastructure.persistence.SysResourceAclDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResourceAclService implements ResourceAclApi {
    private final SysResourceAclRepository acls;
    private final ResourceGuard guard;
    private final PrincipalHolder principals;
    private final AuthUserRepository users;
    private final SysGroupRepository groups;

    public ResourceAclService(SysResourceAclRepository acls,
                              ResourceGuard guard,
                              PrincipalHolder principals,
                              AuthUserRepository users,
                              SysGroupRepository groups) {
        this.acls = acls;
        this.guard = guard;
        this.principals = principals;
        this.users = users;
        this.groups = groups;
    }

    @Override
    public void ensureOwner(String resourceType, String resourceId, String userId) {
        acls.upsert(resourceType, resourceId, "user", userId, AccessLevel.OWNER.name());
    }

    @Override
    public List<AclEntry> list(String resourceType, String resourceId) {
        requireShare(resourceType, resourceId);
        List<AclEntry> out = new ArrayList<>();
        for (SysResourceAclDO e : acls.listByResource(resourceType, resourceId)) {
            out.add(new AclEntry(e.getSubjectType(), e.getSubjectId(),
                    resolveName(e.getSubjectType(), e.getSubjectId()),
                    AccessLevel.valueOf(e.getLevel())));
        }
        return out;
    }

    @Override
    @Transactional
    public void replace(String resourceType, String resourceId, List<AclEntry> entries) {
        requireShare(resourceType, resourceId);
        if (entries == null || entries.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "必须保留至少一个 OWNER");
        }
        boolean hasOwner = entries.stream().anyMatch(e -> e.level() == AccessLevel.OWNER);
        if (!hasOwner) {
            throw new BizException(ErrorCode.BAD_REQUEST, "必须保留至少一个 OWNER");
        }
        List<SysResourceAclDO> rows = new ArrayList<>();
        for (AclEntry e : entries) {
            SysResourceAclDO row = new SysResourceAclDO();
            row.setSubjectType(e.subjectType());
            row.setSubjectId(e.subjectId());
            row.setLevel(e.level().name());
            rows.add(row);
        }
        acls.replace(resourceType, resourceId, rows);
    }

    private void requireShare(String resourceType, String resourceId) {
        Principal p = principals.current();
        String sharePerm = switch (resourceType) {
            case "agent" -> Permissions.AGENT_SHARE;
            case "kb" -> Permissions.KB_SHARE;
            default -> resourceType + ":share";
        };
        if (!p.hasPermission(sharePerm) && !p.hasPermission(Permissions.ALL)) {
            // 无独立 share 权限点的资源类型：要求 write + OWNER
            if (!p.hasPermission(resourceType + ":write")) {
                throw new BizException(ErrorCode.FORBIDDEN, "缺少 " + sharePerm);
            }
        }
        guard.check(resourceType, resourceId, AccessLevel.OWNER);
    }

    private String resolveName(String subjectType, String subjectId) {
        if ("user".equals(subjectType)) {
            try {
                AuthUserDO u = users.findById(Long.parseLong(subjectId));
                return u == null ? subjectId : (u.getDisplayName() == null ? u.getUsername() : u.getDisplayName());
            } catch (NumberFormatException e) {
                return subjectId;
            }
        }
        if ("group".equals(subjectType)) {
            SysGroupDO g = groups.findById(subjectId);
            return g == null ? subjectId : g.getName();
        }
        return subjectId;
    }
}
