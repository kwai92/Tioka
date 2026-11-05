package com.tioka.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.ImmutableSnapshotDO;

@TableName("agent_version")
public class AgentVersionDO extends ImmutableSnapshotDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String agentId;
    private String instructions;
    private String routingPlanId;
    private String mcpGrantIds;
    private String skillBindings;
    private String snapshotHash;
    private Boolean published;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public String getRoutingPlanId() { return routingPlanId; }
    public void setRoutingPlanId(String routingPlanId) { this.routingPlanId = routingPlanId; }
    public String getMcpGrantIds() { return mcpGrantIds; }
    public void setMcpGrantIds(String mcpGrantIds) { this.mcpGrantIds = mcpGrantIds; }
    public String getSkillBindings() { return skillBindings; }
    public void setSkillBindings(String skillBindings) { this.skillBindings = skillBindings; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
}
