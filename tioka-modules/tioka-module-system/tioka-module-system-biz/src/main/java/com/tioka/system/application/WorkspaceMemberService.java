package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.event.MemberRemovedEvent;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.application.lifecycle.AfterCommitEvents;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysRoleRepository;
import com.tioka.system.infrastructure.dataobject.SysMemberDO;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.persistence.SysRoleDO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WorkspaceMemberService {
    private final WorkspaceService workspaces;
    private final SysMemberRepository members;
    private final SysRoleRepository roles;
    private final AuthUserRepository users;
    private final PrincipalLoader principalCache;
    private final PrincipalHolder principals;
    private final ApplicationEventPublisher events;
    private final AuditApi audit;

    public WorkspaceMemberService(WorkspaceService workspaces,
                                  SysMemberRepository members,
                                  SysRoleRepository roles,
                                  AuthUserRepository users,
                                  PrincipalLoader principalCache,
                                  PrincipalHolder principals,
                                  ApplicationEventPublisher events,
                                  AuditApi audit) {
        this.workspaces = workspaces;
        this.members = members;
        this.roles = roles;
        this.users = users;
        this.principalCache = principalCache;
        this.principals = principals;
        this.events = events;
        this.audit = audit;
    }

    public Page<MemberView> list(String workspaceId, int page, int size) {
        requireManageWorkspace(workspaceId);
        workspaces.require(workspaceId);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * s;
        List<SysMemberDO> rows = members.listByWorkspace(workspaceId, offset, s);
        List<MemberView> items = new ArrayList<>();
        for (SysMemberDO m : rows) {
            AuthUserDO u = users.findById(Long.parseLong(m.getUserId()));
            items.add(new MemberView(
                    m.getUserId(),
                    u == null ? null : u.getUsername(),
                    u == null ? null : u.getDisplayName(),
                    m.getRoleCode()));
        }
        return new Page<>(items, members.countByWorkspace(workspaceId), p, s);
    }

    @Transactional
    public void put(String workspaceId, String userId, String role) {
        requireManageWorkspace(workspaceId);
        workspaces.require(workspaceId);
        SysRoleDO r = roles.findByCode(role);
        if (r == null || !"WORKSPACE".equalsIgnoreCase(r.getScope())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "role 必须是 WORKSPACE 范围角色");
        }
        if (users.findById(Long.parseLong(userId)) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        boolean demotingAdmin = !"workspace_admin".equals(role)
                && members.listByWorkspace(workspaceId, 0, 5000).stream()
                .anyMatch(m -> userId.equals(m.getUserId()) && "workspace_admin".equals(m.getRoleCode()));
        members.replaceWorkspaceRole(workspaceId, userId, role);
        if (demotingAdmin && members.countWorkspaceAdmins(workspaceId) == 0) {
            throw new BizException(ErrorCode.LAST_ADMIN);
        }
        principalCache.evictUser(Long.parseLong(userId));
    }

    @Transactional
    public void remove(String workspaceId, String userId) {
        requireManageWorkspace(workspaceId);
        workspaces.require(workspaceId);
        boolean isAdmin = members.listByWorkspace(workspaceId, 0, 5000).stream()
                .anyMatch(m -> userId.equals(m.getUserId()) && "workspace_admin".equals(m.getRoleCode()));
        if (isAdmin && members.countWorkspaceAdmins(workspaceId) <= 1) {
            throw new BizException(ErrorCode.LAST_ADMIN);
        }
        members.removeUser(workspaceId, userId);
        // 同步清缓存；事件 afterCommit 后监听方再清一次并取消 Turn（FND-04e）
        principalCache.evictUser(Long.parseLong(userId));
        String operatorId = principals.current().userId();
        Instant at = Instant.now();
        audit.record(new AuditEvent(null, at, null, workspaceId,
                "user", operatorId, "member.remove", "workspace_member",
                workspaceId + ":" + userId,
                AuditOutcome.SUCCESS, null, null, null,
                Map.of("userId", userId, "source", MemberRemovedEvent.SOURCE_MANUAL)));
        AfterCommitEvents.publish(events,
                new MemberRemovedEvent(workspaceId, userId, MemberRemovedEvent.SOURCE_MANUAL));
    }

    /**
     * 路径中的 workspaceId 必须是当前 Principal 所在空间，或调用方为 tenant_admin。
     */
    private void requireManageWorkspace(String workspaceId) {
        var p = principals.current();
        if (!p.hasPermission(Permissions.WORKSPACE_MANAGE)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (p.hasPermission("*") || p.roles().contains("tenant_admin")) {
            return;
        }
        if (workspaceId == null || !workspaceId.equals(p.workspaceId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能管理其他空间的成员");
        }
    }

    public record MemberView(String userId, String username, String displayName, String role) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
}
