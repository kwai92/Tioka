package com.tioka.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("agent_agent")
public class AgentDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String workspaceId;
    private Boolean enabled;
    private String publishedVersionId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getPublishedVersionId() { return publishedVersionId; }
    public void setPublishedVersionId(String publishedVersionId) { this.publishedVersionId = publishedVersionId; }
}
