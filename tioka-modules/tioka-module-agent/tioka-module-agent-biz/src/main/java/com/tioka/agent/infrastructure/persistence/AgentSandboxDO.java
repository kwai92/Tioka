package com.tioka.agent.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;
@TableName("agent_sandbox")
public class AgentSandboxDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String agentId;
    private String level;
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
}
