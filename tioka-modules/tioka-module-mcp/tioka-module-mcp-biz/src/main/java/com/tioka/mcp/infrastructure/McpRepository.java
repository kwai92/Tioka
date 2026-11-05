package com.tioka.mcp.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.mcp.infrastructure.mapper.McpGrantMapper;
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
    private final McpToolMapper tools;
    private final McpGrantMapper grants;

    public McpRepository(McpServerMapper servers, McpToolMapper tools, McpGrantMapper grants) {
        this.servers = servers;
        this.tools = tools;
        this.grants = grants;
    }

    public void upsertServer(McpServerDO row) {
        if (servers.selectById(row.getId()) == null) servers.insert(row);
        else servers.updateById(row);
    }

    public McpServerDO findServer(String id) { return servers.selectById(id); }

    public List<McpServerDO> listServers() { return servers.selectList(null); }

    public void replaceTools(String serverId, List<McpToolDO> rows) {
        tools.physicalDeleteByServerId(serverId);
        for (McpToolDO row : rows) tools.insert(row);
    }

    public List<McpToolDO> listTools(String serverId) {
        return tools.selectList(Wrappers.<McpToolDO>lambdaQuery().eq(McpToolDO::getServerId, serverId));
    }

    public void upsertGrant(McpGrantDO row) {
        if (grants.selectById(row.getId()) == null) grants.insert(row);
        else grants.updateById(row);
    }

    public McpGrantDO findGrant(String id) { return grants.selectById(id); }

    public List<McpGrantDO> listGrants() { return grants.selectList(null); }
}
