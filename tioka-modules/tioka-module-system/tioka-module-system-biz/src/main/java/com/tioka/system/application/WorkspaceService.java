package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.infrastructure.SysWorkspaceRepository;
import com.tioka.system.infrastructure.persistence.SysWorkspaceDO;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WorkspaceService {
    private final SysWorkspaceRepository workspaces;

    public WorkspaceService(SysWorkspaceRepository workspaces) {
        this.workspaces = workspaces;
        if (workspaces.findById("ws_default") == null) {
            SysWorkspaceDO def = new SysWorkspaceDO();
            def.setId("ws_default");
            def.setName("默认空间");
            def.setOwnerUserId(1L);
            workspaces.insert(def);
        }
    }

    public Workspace create(String name, Long ownerUserId) {
        String id = "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SysWorkspaceDO row = new SysWorkspaceDO();
        row.setId(id);
        row.setName(name);
        row.setOwnerUserId(ownerUserId);
        workspaces.insert(row);
        return new Workspace(id, name, ownerUserId);
    }

    public Workspace require(String id) {
        SysWorkspaceDO row = workspaces.findById(id);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "空间不存在");
        return new Workspace(row.getId(), row.getName(), row.getOwnerUserId());
    }

    public void assertMember(String workspaceId, Long userId) {
        Workspace w = require(workspaceId);
        if (!w.ownerUserId().equals(userId) && userId != null && userId != 1L) {
            throw new BizException(ErrorCode.FORBIDDEN, "无空间权限");
        }
    }

    public record Workspace(String id, String name, Long ownerUserId) {}
}
