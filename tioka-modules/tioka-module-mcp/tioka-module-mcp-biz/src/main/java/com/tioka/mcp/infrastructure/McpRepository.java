package com.tioka.mcp.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.mcp.infrastructure.mapper.McpGrantMapper;
import com.tioka.mcp.infrastructure.mapper.McpServerLockMapper;
import com.tioka.mcp.infrastructure.mapper.McpServerMapper;
import com.tioka.mcp.infrastructure.mapper.McpToolMapper;
import com.tioka.mcp.infrastructure.persistence.McpGrantDO;
import com.tioka.mcp.infrastructure.persistence.McpServerDO;
import com.tioka.mcp.infrastructure.persistence.McpToolDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class McpRepository {
    private final McpServerMapper servers;
    private final McpServerLockMapper serverLocks;
    private final McpToolMapper tools;
    private final McpGrantMapper grants;

    public McpRepository(McpServerMapper servers, McpServerLockMapper serverLocks,
                         McpToolMapper tools, McpGrantMapper grants) {
        this.servers = servers;
        this.serverLocks = serverLocks;
        this.tools = tools;
        this.grants = grants;
    }

    public void upsertServer(McpServerDO row) {
        if (servers.selectById(row.getId()) == null) servers.insert(row);
        else servers.updateById(row);
    }

    public McpServerDO findServer(String id) { return servers.selectById(id); }

    /** AST-02：FOR SHARE 锁定 mcp_server。 */
    public McpServerDO lockServerForShare(String id) { return serverLocks.selectForShare(id); }

    public List<McpServerDO> listServers() { return servers.selectList(null); }

    public void replaceTools(String serverId, List<McpToolDO> rows) {
        tools.physicalDeleteByServerId(serverId);
        for (McpToolDO row : rows) tools.insert(row);
    }

    public List<McpToolDO> listTools(String serverId) {
        return tools.selectList(Wrappers.<McpToolDO>lambdaQuery().eq(McpToolDO::getServerId, serverId));
    }

    public McpToolDO findTool(String serverId, String toolName) {
        return tools.selectOne(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getServerId, serverId)
                .eq(McpToolDO::getName, toolName));
    }

    /** @return false 若工具不存在 */
    public boolean updateToolRisk(String serverId, String toolName, String risk) {
        McpToolDO row = findTool(serverId, toolName);
        if (row == null) return false;
        row.setRisk(risk);
        tools.updateById(row);
        return true;
    }

    public void upsertGrant(McpGrantDO row) {
        if (grants.selectById(row.getId()) == null) grants.insert(row);
        else grants.updateById(row);
    }

    public McpGrantDO findGrant(String id) { return grants.selectById(id); }

    public List<McpGrantDO> listGrants() { return grants.selectList(null); }
}
