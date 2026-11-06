package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysWorkspaceRepository;
import com.tioka.system.infrastructure.dataobject.SysMemberDO;
import com.tioka.system.infrastructure.persistence.SysWorkspaceDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class WorkspaceService {
    private final SysWorkspaceRepository workspaces;
    private final SysMemberRepository members;
    private final PrincipalHolder principals;

    public WorkspaceService(SysWorkspaceRepository workspaces,
                            SysMemberRepository members,
                            PrincipalHolder principals) {
        this.workspaces = workspaces;
        this.members = members;
        this.principals = principals;
    }

    @Transactional
    public Workspace create(String name) {
        requireTenantAdmin();
        long ownerId = Long.parseLong(principals.current().userId());
        String id = Ids.newId("ws");
        SysWorkspaceDO row = new SysWorkspaceDO();
        row.setId(id);
        row.setName(name);
        row.setOwnerUserId(ownerId);
        workspaces.insert(row);
        members.upsert(id, String.valueOf(ownerId), "workspace_admin");
        return new Workspace(id, name, ownerId);
    }

    private void requireTenantAdmin() {
        if (!principals.current().hasPermission("*")
                && !principals.current().roles().contains("tenant_admin")) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    /** 测试 / 引导用：指定所有者创建空间。 */
    @Transactional
    public Workspace create(String name, Long ownerUserId) {
        String id = Ids.newId("ws");
        SysWorkspaceDO row = new SysWorkspaceDO();
        row.setId(id);
        row.setName(name);
        row.setOwnerUserId(ownerUserId);
        workspaces.insert(row);
        members.upsert(id, String.valueOf(ownerUserId), "workspace_admin");
        return new Workspace(id, name, ownerUserId);
    }

    public Workspace require(String id) {
        SysWorkspaceDO row = workspaces.findById(id);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "空间不存在");
        return new Workspace(row.getId(), row.getName(), row.getOwnerUserId());
    }

    /** GET /workspaces/{id}：仅本空间成员或 tenant_admin 可读。 */
    public Workspace requireAccessible(String id) {
        Workspace ws = require(id);
        var p = principals.current();
        if (p.hasPermission("*") || p.roles().contains("tenant_admin")) {
            return ws;
        }
        if (id.equals(p.workspaceId())) {
            return ws;
        }
        assertMember(id, p.userId() == null ? null : Long.parseLong(p.userId()));
        return ws;
    }

    public void assertMember(String workspaceId, Long userId) {
        require(workspaceId);
        if (userId == null || !members.isMember(workspaceId, String.valueOf(userId))) {
            throw new BizException(ErrorCode.WORKSPACE_FORBIDDEN, "不是该空间成员");
        }
    }

    public List<MineWorkspace> mine() {
        String userId = principals.current().userId();
        List<SysMemberDO> ms = members.findByUserId(userId);
        List<MineWorkspace> out = new ArrayList<>();
        for (SysMemberDO m : ms) {
            SysWorkspaceDO ws = workspaces.findById(m.getWorkspaceId());
            if (ws == null) continue;
            out.add(new MineWorkspace(ws.getId(), ws.getName(), m.getRoleCode()));
        }
        return out;
    }

    public record Workspace(String id, String name, Long ownerUserId) {}
    public record MineWorkspace(String id, String name, String role) {}
}
