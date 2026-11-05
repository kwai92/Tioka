package com.tioka.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("mcp_grant")
public class McpGrantDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String agentId;
    private String serverId;
    private String allowlist;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }
    public String getAllowlist() { return allowlist; }
    public void setAllowlist(String allowlist) { this.allowlist = allowlist; }
}
