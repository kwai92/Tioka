package com.tioka.system.application.authz;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.framework.security.VisibilityFilter;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.infrastructure.ResourceLookupRepository;
import com.tioka.system.infrastructure.SysResourceAclRepository;
import com.tioka.system.infrastructure.persistence.SysResourceAclDO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源级 ACL / visibility 判定（FND-04 / M1-01）。
 */
@Service
public class DefaultResourceGuard implements ResourceGuard {
    private final PrincipalHolder principals;
    private final PrincipalLoader principalLoader;
    private final ResourceLookupRepository resources;
    private final SysResourceAclRepository acls;
    private final AuditApi audit;

    public DefaultResourceGuard(PrincipalHolder principals,
                                PrincipalLoader principalLoader,
                                ResourceLookupRepository resources,
                                SysResourceAclRepository acls,
                                AuditApi audit) {
        this.principals = principals;
        this.principalLoader = principalLoader;
        this.resources = resources;
        this.acls = acls;
        this.audit = audit;
    }

    @Override
    public void check(String resourceType, String resourceId, AccessLevel level) {
        Decision d = decide(resourceType, resourceId, level);
        if (d == Decision.NOT_FOUND) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        if (d == Decision.DENIED) {
            Principal p = principals.current();
            try {
                audit.recordIndependent(new AuditEvent(
                        null, Instant.now(), null, p.workspaceId(),
                        p.type().name().toLowerCase(), p.userId(),
                        resourceType + "." + level.name().toLowerCase(),
                        resourceType, resourceId, AuditOutcome.DENIED,
                        "资源权限不足", null, null, null));
            } catch (RuntimeException ignored) {
                // ignore
            }
            // VIEW/USE 不足时 404，避免暴露 PRIVATE 资源存在性；EDIT/OWNER 不足时 403
            if (level.ordinal() <= AccessLevel.USE.ordinal()) {
                throw new BizException(ErrorCode.NOT_FOUND, "资源不存在");
            }
            throw new BizException(ErrorCode.FORBIDDEN, "无资源权限");
        }
    }

    @Override
    public boolean allows(String resourceType, String resourceId, AccessLevel level) {
        return decide(resourceType, resourceId, level) == Decision.ALLOW;
    }

    @Override
    public Set<String> visibleIds(String resourceType, AccessLevel level) {
        Principal p = principals.current();
        if (isAdmin(p, resourceType)) {
            return new HashSet<>(resources.listIdsInWorkspace(resourceType, p.workspaceId()));
        }
        Set<String> out = new HashSet<>();
        for (String id : resources.listIdsInWorkspace(resourceType, p.workspaceId())) {
            if (allows(resourceType, id, level)) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public VisibilityFilter filter(String resourceType, AccessLevel level) {
        Principal p = principals.current();
        if (isAdmin(p, resourceType)) {
            return VisibilityFilter.allowAll();
        }
        List<String> groupIds = principalLoader.groupIds(p.userId());
        List<String> levels = levelsAtLeast(level).stream().map(Enum::name).toList();
        String levelIn = levels.stream().map(l -> "'" + l + "'").collect(Collectors.joining(","));
        StringBuilder groupClause = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        params.put("uid", p.userId());
        params.put("ws", p.workspaceId());
        if (groupIds.isEmpty()) {
            groupClause.append("FALSE");
        } else {
            List<String> ph = new ArrayList<>();
            for (int i = 0; i < groupIds.size(); i++) {
                String key = "gid" + i;
                params.put(key, groupIds.get(i));
                ph.add("#{" + key + "}");
            }
            groupClause.append("(subject_type = 'group' AND subject_id IN (")
                    .append(String.join(",", ph)).append("))");
        }

        String aclSub = """
                id IN (
                  SELECT resource_id FROM sys_resource_acl
                  WHERE resource_type = '%s'
                    AND level IN (%s)
                    AND (
                      (subject_type = 'user' AND subject_id = #{uid})
                      OR (subject_type = 'workspace' AND subject_id = #{ws})
                      OR %s
                    )
                )
                """.formatted(resourceType, levelIn, groupClause);

        StringBuilder sql = new StringBuilder("(").append(aclSub);
        // 空间默认可见性
        String readPerm = resourceType + ":read";
        String writePerm = resourceType + ":write";
        boolean workspaceUse = level.ordinal() <= AccessLevel.USE.ordinal() && p.hasPermission(readPerm);
        boolean workspaceEdit = level.ordinal() <= AccessLevel.EDIT.ordinal() && p.hasPermission(writePerm);
        if (level == AccessLevel.OWNER) {
            // OWNER 仅 ACL
        } else if (workspaceEdit || (level.ordinal() <= AccessLevel.USE.ordinal() && workspaceUse)) {
            sql.append(" OR visibility = 'WORKSPACE'");
        }
        sql.append(")");
        return VisibilityFilter.of(sql.toString(), params);
    }

    private Decision decide(String resourceType, String resourceId, AccessLevel level) {
        Principal p = principals.current();
        if (isAdmin(p, resourceType)) {
            return Decision.ALLOW;
        }
        ResourceLookupRepository.ResourceRow row = resources.find(resourceType, resourceId);
        if (row == null) {
            return Decision.NOT_FOUND;
        }
        if (row.workspaceId() == null || !row.workspaceId().equals(p.workspaceId())) {
            return Decision.NOT_FOUND;
        }
        AccessLevel aclMax = maxAclLevel(resourceType, resourceId, p);
        if (aclMax != null && aclMax.covers(level)) {
            return Decision.ALLOW;
        }
        if ("WORKSPACE".equalsIgnoreCase(row.visibility()) && level != AccessLevel.OWNER) {
            if (level.ordinal() <= AccessLevel.USE.ordinal()
                    && p.hasPermission(resourceType + ":read")) {
                return Decision.ALLOW;
            }
            if (level.ordinal() <= AccessLevel.EDIT.ordinal()
                    && p.hasPermission(resourceType + ":write")) {
                return Decision.ALLOW;
            }
        }
        return Decision.DENIED;
    }

    private AccessLevel maxAclLevel(String resourceType, String resourceId, Principal p) {
        List<String> groupIds = principalLoader.groupIds(p.userId());
        Set<String> groupSet = new HashSet<>(groupIds);
        AccessLevel max = null;
        for (SysResourceAclDO e : acls.listByResource(resourceType, resourceId)) {
            boolean match = false;
            if ("user".equals(e.getSubjectType()) && p.userId().equals(e.getSubjectId())) {
                match = true;
            } else if ("workspace".equals(e.getSubjectType()) && p.workspaceId().equals(e.getSubjectId())) {
                match = true;
            } else if ("group".equals(e.getSubjectType()) && groupSet.contains(e.getSubjectId())) {
                match = true;
            }
            if (!match) {
                continue;
            }
            AccessLevel lv = AccessLevel.valueOf(e.getLevel());
            if (max == null || lv.ordinal() > max.ordinal()) {
                max = lv;
            }
        }
        return max;
    }

    private static boolean isAdmin(Principal p, String resourceType) {
        return p.hasPermission("*") || p.hasPermission(resourceType + ":admin");
    }

    private static EnumSet<AccessLevel> levelsAtLeast(AccessLevel required) {
        EnumSet<AccessLevel> set = EnumSet.noneOf(AccessLevel.class);
        for (AccessLevel l : AccessLevel.values()) {
            if (l.covers(required)) {
                set.add(l);
            }
        }
        return set;
    }

    private enum Decision { ALLOW, DENIED, NOT_FOUND }
}
