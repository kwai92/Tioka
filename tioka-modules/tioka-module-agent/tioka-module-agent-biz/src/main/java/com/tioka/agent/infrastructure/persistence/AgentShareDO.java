package com.tioka.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("agent_share")
public class AgentShareDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String agentId;
    private String mode;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
