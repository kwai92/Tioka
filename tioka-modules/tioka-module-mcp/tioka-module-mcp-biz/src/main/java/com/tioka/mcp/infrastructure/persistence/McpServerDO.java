package com.tioka.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("mcp_server")
public class McpServerDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String baseUrl;
    private String headerSecretRef;
    private String status;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getHeaderSecretRef() { return headerSecretRef; }
    public void setHeaderSecretRef(String headerSecretRef) { this.headerSecretRef = headerSecretRef; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
