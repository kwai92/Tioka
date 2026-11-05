package com.tioka.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("mcp_tool")
public class McpToolDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String serverId;
    private String name;
    private String description;
    private String inputSchema;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
}
